(ns akvo.lumen.update
  (:require [akvo.lumen.import.common :as import]
            [akvo.lumen.import.csv]
            [akvo.lumen.import.flow]
            [akvo.lumen.lib :as lib]
            [akvo.lumen.transformation.engine :as engine]
            [akvo.lumen.util :as util]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as string]
            [hugsql.core :as hugsql]))

(hugsql/def-db-fns "akvo/lumen/job-execution.sql")
(hugsql/def-db-fns "akvo/lumen/transformation.sql")
(hugsql/def-db-fns "akvo/lumen/dataset.sql")

(defn successful-update
  "On a successful update we need to create a new dataset-version that
  is similar to the previous one, except with an updated :version and
  pointing to the new table-name, imported-table-name and columns. We
  also delete the previous table-name and imported-table-name so we
  don't accumulate unused datasets on each update."
  [conn job-execution-id dataset-id table-name imported-table-name dataset-version new-columns]
  (insert-dataset-version conn {:id (str (util/squuid))
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

(defn failed-update [conn job-execution-id reason]
  (update-failed-job-execution conn {:id job-execution-id
                                     :reason [reason]}))

(defn apply-transformation-log [conn table-name columns transformations]
  (let [;; Translate columns vector into a form that the transformation engine understands
        columns (mapv (fn [{:keys [title id type key] :as column}]
                        (cond-> {"type" (name type)
                                 "title" title
                                 "columnName" (name id)
                                 "sort" nil
                                 "direction" nil
                                 "hidden" false}
                          (contains? column :key) (assoc "key" (boolean key))) )
                      columns)]
    (loop [transformations transformations columns columns]
      (if-let [transformation (first transformations)]
        (let [{:keys [success? message columns]} (engine/try-apply-operation conn table-name columns transformation)]
          (when-not success?
            (throw (ex-info (format "Failed to update due to transformation mismatch: %s"
                                    message)
                            {})))
          (recur (rest transformations) columns))
        columns))))

(defn compatible-columns? [imported-columns columns]
  (let [imported-columns (map (fn [column]
                                (cond-> (let [caddisflyResourceUuid (get column "caddisflyResourceUuid")]
                                          (merge {:id (keyword (get column "columnName"))
                                                  :type (keyword (get column "type"))
                                                  :title (string/trim (get column "title"))}
                                                 (when caddisflyResourceUuid
                                                     {:caddisflyResourceUuid caddisflyResourceUuid})))
                                  (contains? column "key") (assoc :key (boolean (get column "key")))))
                              imported-columns)]
    (set/subset? (set imported-columns)
                 (set columns))))

(defn do-update [conn config dataset-id data-source-id job-execution-id data-source-spec]
  (try
    (let [table-name (util/gen-table-name "ds")
          imported-table-name (util/gen-table-name "imported")
          imported-columns (vec
                            (:columns (imported-dataset-columns-by-dataset-id
                                       conn {:dataset-id dataset-id})))
          {:keys [transformations] :as dataset-version} (latest-dataset-version-by-dataset-id
                                                         conn {:dataset-id dataset-id})]
      (with-open [importer (import/dataset-importer (get data-source-spec "source") config)]
        (let [columns (map #(update % :title string/trim)
                           (import/columns importer))]
          (if-not (compatible-columns? imported-columns columns)
            (failed-update conn job-execution-id "Column mismatch")
            (do (import/create-dataset-table conn table-name columns)
                (import/add-key-constraints conn table-name columns)
                (doseq [record (map import/coerce-to-sql (import/records importer))]
                  (jdbc/insert! conn table-name record))
                (clone-data-table conn
                                  {:from-table table-name
                                   :to-table imported-table-name}
                                  {}
                                  {:transaction? false})
                (let [new-columns (apply-transformation-log conn
                                                            table-name
                                                            columns
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

(defn update-dataset [tenant-conn config dataset-id data-source-id data-source-spec]
  (let [job-execution-id (str (util/squuid))]
    (insert-dataset-update-job-execution tenant-conn {:id job-execution-id
                                                      :data-source-id data-source-id})
    (future (do-update tenant-conn
                       config
                       dataset-id
                       data-source-id
                       job-execution-id
                       data-source-spec))
    (lib/ok {"updateId" job-execution-id})))
