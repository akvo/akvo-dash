(ns akvo.lumen.lib.transformation.merge-datasets
  (:require [akvo.lumen.db.data-group :as db.data-group]
            [akvo.lumen.db.dataset :as db.dataset]
            [akvo.lumen.db.dataset-version :as db.dataset-version]
            [akvo.lumen.db.transformation :as db.transformation]
            [akvo.lumen.db.transformation.engine :as db.tx.engine]
            [akvo.lumen.lib.env :as env]
            [akvo.lumen.lib.transformation.engine :as engine]
            [akvo.lumen.lib.data-group :as data-group]
            [akvo.lumen.postgres :as postgres]
            [akvo.lumen.util :as util]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set :refer [rename-keys]]
            [clojure.string :as s]
            [clojure.tools.logging :as log]
            [clojure.walk :as walk])
  (:import [java.sql Timestamp]
           [org.postgis PGgeometry]))

(declare reset-column-values)

(defmethod engine/valid? "core/merge-datasets"
  [op-spec]
  (let [source (get-in op-spec ["args" "source"])
        target (get-in op-spec ["args" "target"])]
    (and (util/valid-column-name? (get source "mergeColumn"))
         (every? util/valid-column-name? (get source "mergeColumns"))
         (util/valid-dataset-id? (get source "datasetId"))
         (let [aggregation-column (get source "aggregationColumn")]
           (or (nil? aggregation-column)
               (util/valid-column-name? aggregation-column)))
         (#{"ASC" "DESC"} (get source "aggregationDirection"))
         (util/valid-column-name? (get target "mergeColumn")))))

(defn merge-column-names-map
  "Returns a map translating source merge column names to new column names that can be used
  in the target dataset"
  [columns merge-columns]
  (loop [m {} columns columns merge-columns merge-columns]
    (if (empty? merge-columns)
      m
      (let [new-column-name (engine/next-column-name columns)
            merge-column (first merge-columns)
            new-merge-column (assoc merge-column "columnName" new-column-name)]
        (recur (assoc m (get merge-column "columnName") new-column-name)
               (conj columns new-merge-column)
               (rest merge-columns))))))

(defn fetch-sql
  [table-name {:strs [mergeColumn mergeColumns aggregationColumn aggregationDirection]}]
  (format "SELECT DISTINCT ON (%1$s) %1$s, %2$s FROM %3$s ORDER BY %1$s, %4$s %5$s NULLS LAST"
          mergeColumn
          (s/join ", " mergeColumns)
          table-name
          (or aggregationColumn mergeColumn)
          aggregationDirection))

(defn ->pggeometry [value]
  (doto
      (PGgeometry/geomFromString value)
    (.setSrid 4326)))

(defn ->timestamp [value]
  (Timestamp. value))

(defn to-sql-types
  [row merge-columns]
  (let [requires-cast? #(contains? #{"date" "geopoint" "geoshape"} (get % "type"))
        columns-requiring-cast (filter requires-cast? merge-columns)]
    (reduce (fn [result-row {:strs [columnName type]}]
              (update result-row columnName
                      (fn [v]
                        (when v (case type
                                  "date" (->timestamp v)
                                  "geopoint" (->pggeometry v)
                                  "geoshape" (->pggeometry v))))))
            row
            columns-requiring-cast)))

(defn fetch-data
  "Fetch data from the source dataset and returns a map with the shape
  {<merge-column-value> {<new-column-name> <value>}}"
  [conn table-name merge-columns column-names-translation source]
  (let [merge-column-name (get source "mergeColumn")
        rows (jdbc/query conn
                         (fetch-sql table-name source)
                         {:keywordize? false})]
    (reduce (fn [result row]
              (let [merge-column-value (get row merge-column-name)
                    data-to-merge (-> row
                                      (dissoc merge-column-name)
                                      (set/rename-keys column-names-translation)
                                      (to-sql-types merge-columns))]
                (assoc result merge-column-value data-to-merge)))
            {}
            rows)))

(defn fetch-data-2
  [conn {:keys [source-data-group source target]}]
  (let [source-selected-columns (filter #(not= "instance_id" (get % "columnName")) (:columns source-data-group))
        source-selected-columns-select  (fn [aliased?]
                                          (if aliased?
                                            (s/join ", "
                                                    (map (fn [{:strs [sourceColumnName columnName]}]
                                                           (format "s.%s as %s" sourceColumnName columnName))
                                                         source-selected-columns))
                                            (s/join ", "
                                                    (map (partial str "s.")
                                                         (map #(get % "sourceColumnName")
                                                              source-selected-columns)))))
        subquery                (format "SELECT s.%1$s, %2$s FROM (%3$s) a, %4$s s WHERE a.instance_id = s.instance_id"
                                        (-> source :merge-column)
                                        (source-selected-columns-select false)
                                        (fetch-sql (-> source :table-name) (-> source
                                                                               :spec
                                                                               (assoc "mergeColumns" ["instance_id"])))
                                        (-> source :table-name))
        sql                     (format "SELECT t.instance_id, %1$s FROM (%2$s) s, %3$s t WHERE t.%4$s = s.%5$s"
                                        (source-selected-columns-select true)
                                        subquery
                                        (-> target :table-name)
                                        (-> target :merge-column)
                                        (-> source :merge-column))]
    (jdbc/query conn [sql] {:keywordize? false})))

(defn add-columns
  "Add the new columns to the target dataset"
  [conn table-name columns]
  (doseq [column columns]
    (db.tx.engine/add-column conn {:table-name table-name
                                   :new-column-name (get column "columnName")
                                   :column-type (condp = (get column "type")
                                                  "date" "timestamptz"
                                                  "geopoint" "geometry(POINT, 4326)"
                                                  "geoshape" "geometry(GEOMETRY, 4326)"
                                                  "number" "double precision"
                                                  "multiple" "text"
                                                  "text" "text"
                                                  "option" "text")})))

(defn insert-merged-data
  "Insert the merged values into the target dataset"
  [conn table-name target data]
  (let [target-merge-column (get target "mergeColumn")]
    (doseq [[merge-value data-map] data]
      (jdbc/update! conn
                    table-name
                    data-map
                    [(str target-merge-column "= ?") merge-value]))))

(defn get-data-groups-to-be-created [conn source target-dataset-columns]
  (let [dataset-id (get source "datasetId")
        dataset-version (db.dataset-version/latest-dataset-version-2-by-dataset-id conn {:dataset-id dataset-id})
        data-groups     (db.data-group/list-data-groups-by-dataset-version-id
                         conn
                         {:dataset-version-id (:id dataset-version)})
        columns-by-group (->> (set (get source "mergeColumns"))
                              (map-indexed (fn [i column-name]
                                             (let [dg (engine/datagroup-by-column data-groups column-name)
                                                   column (first (filter #(= (get % "columnName") column-name) (:columns dg)))]
                                               (-> column
                                                   (assoc "sourceColumnName" (get column "columnName")
                                                          "columnName" (engine/derivation-column-name
                                                                        (+ (engine/next-column-index target-dataset-columns) i)))
                                                   reset-column-values))))
                              (group-by #(get % "groupId")))
        instance-id-column (->> (db.data-group/get-data-group-by-column-name
                                 conn
                                 {:column-name "instance_id"
                                  :dataset-version-id (:id dataset-version) })
                                :columns
                                (filter #(= "instance_id" (get % "columnName")))
                                first)]
    (map (fn [[group-id columns]]
           (let [dg (first (filter #(= (:group-id %) group-id) data-groups))
                 instance-id (merge instance-id-column {:groupId group-id
                                                        :groupName (:groupName dg)
                                                        :key false
                                                        :hidden true})
                 instance-id-exists? (boolean (first (filter #(= "instance_id" (get % "columnName")) columns)))]
             (assoc dg
                    :columns (if instance-id-exists?
                               columns
                               (conj columns instance-id))
                    :table-name (util/gen-table-name "ds")
                    :source-table-name (:table-name dg)
                    :imported-table-name "MERGE_DATASET"))) columns-by-group)))

(defn get-source-dataset [conn source]
  (let [source-dataset-id (get source "datasetId")]
    (if-let [source-dataset (db.transformation/latest-dataset-version-by-dataset-id conn {:dataset-id source-dataset-id})]
      source-dataset
      (throw (ex-info (format "Dataset %s does not exist" source-dataset-id)
                      {:source source})))))

(defn- get-source-merge-columns [source source-dataset-columns]
  (let [column-names (set (get source "mergeColumns"))]
    (filterv (fn [column]
               (contains? column-names
                          (get column "columnName")))
             source-dataset-columns)))

(defn reset-column-values [c]
  (assoc c
         "sort" nil
         "direction" nil
         "key" false
         "hidden" false))

(defn get-target-merge-columns [source-merge-columns column-names-translation]
  (mapv #(-> %
             (update "columnName" column-names-translation)
             reset-column-values)
        source-merge-columns))

(defn apply-merge-operation-2
  [conn table-name columns op-spec]
  (let [source (get-in op-spec ["args" "source"])
        target (get-in op-spec ["args" "target"])
        data-groups-to-be-created (get-data-groups-to-be-created conn source columns)
        target-dataset-version (db.dataset-version/latest-dataset-version-2-by-dataset-id conn {:dataset-id (:dataset-id op-spec) })
        target-merge-data-group (db.data-group/get-data-group-by-column-name conn {:column-name (get target "mergeColumn")
                                                                                   :dataset-version-id (:id target-dataset-version)})
        source-dataset (data-group/table-name-and-columns-from-data-grops conn (get source "datasetId"))]
    (doseq [{:keys [columns table-name] :as source-data-group}  data-groups-to-be-created]
      (let [data (->>
                  (fetch-data-2 conn {:source-data-group source-data-group
                                      :source {:merge-column (get source "mergeColumn")
                                               :table-name (:table-name source-dataset)
                                               :aggregation-column (get source "aggregationColumn")
                                               :aggregation-direction (get source "aggregationDirection")
                                               :spec source}
                                      :target {:merge-column (get target "mergeColumn")
                                               :table-name (:table-name target-merge-data-group)}})
                  (map #(to-sql-types % (walk/stringify-keys columns))))]
        (postgres/create-dataset-table conn table-name (map #(update % :id (fn [_]
                                                                             (:columnName %)))
                                                            (walk/keywordize-keys columns)))
        (jdbc/insert-multi! conn table-name data)))
    {:success? true
     :data-groups-to-be-created data-groups-to-be-created
     :execution-log [(format "Merged columns from %s into %s"
                             "(:table-name source-dataset)"
                             table-name)]
     :columns (vec columns)}))

(defn apply-merge-operation
  [conn table-name columns op-spec]
  (let [source (get-in op-spec ["args" "source"])
        target (get-in op-spec ["args" "target"])
        source-dataset (get-source-dataset conn source)
        source-merge-columns (get-source-merge-columns source (:columns source-dataset))
        column-names-translation (merge-column-names-map columns
                                                         source-merge-columns)
        target-merge-columns (get-target-merge-columns source-merge-columns
                                                       column-names-translation)
        data (fetch-data conn (:table-name source-dataset)
                         target-merge-columns
                         column-names-translation
                         source)]
    (add-columns conn table-name target-merge-columns)
    (insert-merged-data conn table-name target data)
    {:success? true
     :execution-log [(format "Merged columns from %s into %s"
                             (:table-name source-dataset)
                             table-name)]
     :columns (into columns target-merge-columns)}))

(defmethod engine/apply-operation "core/merge-datasets"
  [{:keys [tenant-conn]} table-name columns op-spec]
  (if (get (env/all tenant-conn) "data-groups")
    (apply-merge-operation-2 tenant-conn table-name columns op-spec)
    (apply-merge-operation tenant-conn table-name columns op-spec)))

(defn- merged-datasets-diff [tenant-conn merged-dataset-sources]
  (let [dataset-ids (mapv :datasetId merged-dataset-sources)
        diff        (set/difference (set dataset-ids)
                                    (set (map :id (db.dataset/select-datasets-by-id tenant-conn {:ids dataset-ids}))))]
    (when (not-empty (filter some? diff))
      {:diff diff})))

(defn distinct-columns
  "returns a distinct collection with the columns that participate in a merge operation"
  [merge-op]
  (distinct
   (filter some?
           (-> (:mergeColumns merge-op)
               (conj (:mergeColumn merge-op))
               (conj (:aggregationColumn merge-op))))))

(defn- merged-columns-diff [dss merge-source-op]
  (let [merged-dataset (some #(when (= (:dataset-id %) (:datasetId merge-source-op)) %) dss)
        columns        (distinct-columns merge-source-op)
        expected-columns (set (map #(get % "columnName") (:columns merged-dataset)))
        diff (set/difference (set columns) expected-columns)]
    (when (not-empty diff)
      {:diff diff
       :dataset-id (:datasetId merge-source-op)})))

(defn consistency-error? [tenant-conn dataset-id]
  (let [merged-sources (->> (db.transformation/latest-dataset-version-by-dataset-id tenant-conn {:dataset-id dataset-id})
                            :transformations
                            walk/keywordize-keys
                            (filter #(= "core/merge-datasets" (:op %)))
                            (map #(-> % :args :source)))]
    (if-let [ds-diff (and (not-empty merged-sources)
                          (merged-datasets-diff tenant-conn merged-sources))]
      {:error "Update failed. This dataset has merged columns from a dataset that no longer exists."
       :dataset-diff ds-diff}
      (when-let [column-diff (when (not-empty merged-sources)
                               (let [dss              (->> {:dataset-ids (mapv :datasetId merged-sources)}
                                                           (db.transformation/latest-dataset-versions-by-dataset-ids tenant-conn)
                                                           (map #(rename-keys % {:dataset_id :dataset-id})))
                                     column-diff-coll (->> merged-sources
                                                           (map (partial merged-columns-diff dss))
                                                           (filter some?))]
                                 (when (not-empty column-diff-coll)
                                   column-diff-coll)))]
        {:error(format "Update failed. This dataset has merged columns that no longer exist in their dataset, %s" (mapv :title (db.dataset/select-datasets-by-id tenant-conn {:ids (mapv :dataset-id column-diff)})))
         :column-diff column-diff}))))

(defn sources-related
  "return the list of transformations sources that use target-dataset-id,
  add `:origin` to each item in collection to keep a reference to the dataset-version
  that contains the transformation
  Example schema returned:
  {:datasetId 'uuid-str,
   :mergeColumn 'str
   :mergeColumns ['str]
   :aggregationColumn 'str
   :aggregationDirection 'str
   :origin {:id 'uuid-str
            :title 'str}}"
  [tenant-conn target-dataset-id]
  (->> (db.transformation/latest-dataset-versions tenant-conn) ;; all dataset_versions
       (filter #(not= target-dataset-id (:dataset_id %))) ;; exclude (target-)dataset(-id)
       (map (fn [dataset-version]
              ;; get source datasets of merge transformations with appended dataset-version as origin
              (->> (walk/keywordize-keys (:transformations dataset-version))
                   (filter #(= "core/merge-datasets" (:op %)))
                   (map #(-> % :args :source))
                   (map #(assoc % :origin {:id    (:dataset_id dataset-version)
                                           :title (:title dataset-version)})))))
       (reduce into []) ;; adapt from (({:a :b})({:c :d})) to [{:a :b}{:c :d}]
       (filter #(= (:datasetId %) target-dataset-id))))

(defn datasets-related
  "return the list of dataset-versions that use target-dataset-id in their merge transformations
  Example schema returned
  [{:id 'uuid-str
    :title 'str}]"
  [tenant-conn target-dataset-id]
  (let [origins (map :origin (sources-related tenant-conn target-dataset-id))]
    (when-not (empty? origins) origins)))

(defmethod engine/columns-used "core/merge-datasets"
  [applied-transformation columns]
  [(-> applied-transformation :args :target :mergeColumn)])
