(ns akvo.lumen.import
  (:require [akvo.commons.psql-util :as pg]
            [akvo.lumen.import.common :as import]
            [akvo.lumen.import.csv]
            [akvo.lumen.import.flow]
            [akvo.lumen.lib :as lib]
            [akvo.lumen.transformation :as t]
            [akvo.lumen.transformation.engine :as transformation]
            [akvo.lumen.util :refer (squuid gen-table-name)]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.data.csv :as csv]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [hugsql.core :as hugsql]))

(hugsql/def-db-fns "akvo/lumen/job-execution.sql")
(hugsql/def-db-fns "akvo/lumen/lib/dataset.sql")
(hugsql/def-db-fns "akvo/lumen/transformation.sql")

(defn successful-import [conn job-execution-id table-name columns spec]
  (let [dataset-id (squuid)
        imported-table-name (gen-table-name "imported")]
    (insert-dataset conn {:id dataset-id
                          :title (get spec "name") ;; TODO Consistent naming. Change on client side?
                          :description (get spec "description" "")})
    (clone-data-table conn
                      {:from-table table-name
                       :to-table imported-table-name}
                      {}
                      {:transaction? false})
    (insert-dataset-version conn {:id (squuid)
                                  :dataset-id dataset-id
                                  :job-execution-id job-execution-id
                                  :table-name table-name
                                  :imported-table-name imported-table-name
                                  :version 1
                                  :columns (mapv (fn [{:keys [title id type]}]
                                                   {:type (name type)
                                                    :title title
                                                    :columnName (name id)
                                                    :sort nil
                                                    :direction nil
                                                    :hidden false})
                                                 columns)
                                  :transformations []})
    (update-successful-job-execution conn {:id job-execution-id})))

(defn successful-update
  "On a successful update we need to create a new dataset-version that
  is similar to the previous one, except with an updated :version and
  pointing to the new table-name, imported-table-name and columns. We
  also delete the previous table-name and imported-table-name so we
  don't accumulate unused datasets on each update."
  [conn job-execution-id dataset-id table-name imported-table-name dataset-version new-columns]
  (insert-dataset-version conn {:id (str (squuid))
                                :dataset-id dataset-id
                                :job-execution-id job-execution-id
                                :table-name table-name
                                :imported-table-name imported-table-name
                                :version (inc (:version dataset-version))
                                :columns new-columns
                                :transformations (vec (:transformations dataset-version))})
  (drop-table (:imported-table-name dataset-version))
  (drop-table (:table-name dataset-version))
  (update-successful-job-execution conn {:id job-execution-id}))

(defn failed-import [conn job-execution-id reason]
  (update-failed-job-execution conn {:id job-execution-id
                                     :reason [reason]}))

(defn failed-update [conn job-execution-id reason]
  (update-failed-job-execution conn {:id job-execution-id
                                     :reason [reason]}))

(defn dataset-table-sql
  [table-name columns]
  (format "create table %s (rnum serial primary key, %s);"
          table-name
          (str/join ", " (map (fn [{:keys [id type]}]
                                (format "%s %s"
                                        (name id)
                                        (condp = type
                                          :date "timestamptz"
                                          :number "double precision"
                                          :text "text")))
                              columns))))

(defn create-dataset-table [conn table-name columns]
  (jdbc/execute! conn [(dataset-table-sql table-name columns)]))

(defprotocol CoerceToSql
  (coerce [this]))

(extend-protocol CoerceToSql
  java.lang.String
  (coerce [value] value)
  java.lang.Number
  (coerce [value] value)
  java.time.Instant
  (coerce [value]
    (java.sql.Timestamp. (.toEpochMilli value))))

(defn coerce-to-sql [record]
  (reduce-kv
   (fn [result k v]
     (assoc result k (when v (coerce v))))
   {}
   record))

(defn do-import [conn config job-execution-id]
  (try
    (let [table-name (gen-table-name "ds")
          spec (:spec (data-source-spec-by-job-execution-id conn {:job-execution-id job-execution-id}))]
      (with-open [importer (import/dataset-importer (get spec "source") config)]
        (let [columns (import/columns importer)]
          (create-dataset-table conn table-name columns)
          (doseq [record (map coerce-to-sql (import/records importer))]
            (jdbc/insert! conn table-name record))
          (successful-import conn job-execution-id table-name columns spec))))
    (catch Exception e
      (failed-import conn job-execution-id (str "Failed to import: " (.getMessage e)))
      (throw e))))

(defn apply-transformation-log [conn table-name columns transformations]
  (loop [transformations transformations columns columns]
    (if-let [transformation (first transformations)]
      (let [{:keys [success?
                    message
                    columns
                    execution-log]} (transformation/apply-operation conn
                                                                    table-name
                                                                    columns
                                                                    transformation)]
        (when-not success?
          (throw (ex-info (format "Failed to update due to transformation mismatch: %s"
                                  message)
                          {})))
        (recur (rest transformations) columns))
      columns)))

(defn compatible-columns? [imported-columns columns]
  (let [imported-columns (map (fn [column]
                                {:id (keyword (get column "columnName"))
                                 :type (keyword (get column "type"))
                                 :title (get column "title")})
                              imported-columns)]
    (set/subset? (set imported-columns)
                 (set columns))))

(defn do-update [conn config dataset-id data-source-id job-execution-id data-source-spec]
  (try
    (let [table-name (gen-table-name "ds")
          imported-table-name (gen-table-name "imported")
          imported-columns (vec
                            (:columns (imported-dataset-columns-by-dataset-id conn
                                                                              {:dataset-id dataset-id})))
          {:keys [transformations] :as dataset-version} (latest-dataset-version-by-dataset-id
                                                         conn {:dataset-id dataset-id})]
      (with-open [importer (import/dataset-importer (get data-source-spec "source") config)]
        (let [columns (import/columns importer)]
          (if-not (compatible-columns? imported-columns columns)
            (failed-update conn job-execution-id "Column mismatch")
            (do (create-dataset-table conn table-name columns)
                (doseq [record (map coerce-to-sql (import/records importer))]
                  (jdbc/insert! conn table-name record))
                (clone-data-table conn
                                  {:from-table table-name
                                   :to-table imported-table-name}
                                  {}
                                  {:transaction? false})
                (let [new-columns (apply-transformation-log conn
                                                            table-name
                                                            (mapv (fn [{:keys [title id type]}]
                                                                    {"type" (name type)
                                                                     "title" title
                                                                     "columnName" (name id)
                                                                     "sort" nil
                                                                     "direction" nil
                                                                     "hidden" false})
                                                                  columns)
                                                            transformations)]
                  (successful-update conn
                                     job-execution-id
                                     dataset-id
                                     table-name
                                     imported-table-name
                                     dataset-version
                                     new-columns)))))))
    (catch clojure.lang.ExceptionInfo e
      (failed-update conn job-execution-id (.getMessage e))
      (throw e))
    (catch Exception e
      (failed-update conn job-execution-id (.getMessage e))
      (throw e))))

(defn handle-import-request [tenant-conn config claims data-source]
  (let [data-source-id (str (squuid))
        job-execution-id (str (squuid))
        table-name (gen-table-name "ds")]
    (insert-data-source tenant-conn {:id data-source-id
                                     :spec (json/generate-string data-source)})
    (insert-job-execution tenant-conn {:id job-execution-id
                                       :data-source-id data-source-id})
    (future (do-import tenant-conn config job-execution-id))
    (lib/ok {"importId" job-execution-id})))

(defn update-dataset [tenant-conn config dataset-id data-source-id data-source-spec]
  (let [job-execution-id (str (squuid))]
    (insert-dataset-update-job-execution tenant-conn {:id job-execution-id
                                                      :data-source-id data-source-id})
    (future (do-update tenant-conn
                       config
                       dataset-id
                       data-source-id
                       job-execution-id
                       data-source-spec))
    (lib/ok {"updateId" job-execution-id})))
