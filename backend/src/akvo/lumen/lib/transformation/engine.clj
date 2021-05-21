(ns akvo.lumen.lib.transformation.engine
  (:require [akvo.lumen.util :as util]
            [akvo.lumen.db.transformation :as db.transformation]
            [akvo.lumen.db.dataset-version :as db.dataset-version]
            [akvo.lumen.db.job-execution :as db.job-execution]
            [akvo.lumen.db.data-group :as db.data-group]
            [akvo.lumen.specs.transformation :as s.transformation]
            [akvo.lumen.lib.aggregation.commons :as aggregation.commons]
            [akvo.lumen.lib.env :as env]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [clojure.walk :as w])
  (:import [java.time Instant]
           [clojure.lang ExceptionInfo]))

(def MERGE-DATASET "MERGE_DATASET")

(defmulti columns-used
  (fn [applied-transformation columns]
    (:op applied-transformation)))

(defmethod columns-used :default
  [applied-transformation columns]
  (throw (ex-info (str "unimplemented defmulti columns-used for tx: " (:op applied-transformation))
                  {:transformation applied-transformation})))

(defmethod columns-used nil
  [applied-transformation columns]
  [])

(defmulti avoidable-if-missing?
  (fn [transformation]
    (:op transformation)))

(defmethod avoidable-if-missing? :default
  [transformation]
  false)

(defn log-ex [e]
  (log/info e))

(defmulti valid?
  "Validate transformation spec"
  (fn [op-spec]
    (op-spec "op")))

(defmethod valid? :default
  [op-spec]
  false)

(defmulti apply-operation
  "Applies a particular operation based on `op` key from spec
   * {:keys [tenant-conn] :as deps}: includes open connection to the database
   * table-name: table on which to operate (ds_<uuid>)
   * columns: in-memory representation of a columns spec
   * op-spec: JSON payload with the operation settings
   spec is a JSON payload with the following keys:
   - \"op\" : operation to perform
   - \"args\" : map with arguments to the operation
   - \"onError\" : Error strategy"
  (fn [deps table-name columns op-spec]
    (get op-spec "op")))

(defmethod apply-operation :default
  [deps table-name columns op-spec]
  (let [msg (str "Unknown operation " (get op-spec "op"))]
    (log/debug msg)
    {:success? false
     :message msg}))

(defn- try-apply-operation
  "invoke apply-operation inside a try-catch"
  [deps table-name columns op-spec]
  (try
    (apply-operation deps table-name columns op-spec)
    (catch Exception e
      (log-ex e)
      {:success? false
       :message (format "Failed to transform: %s" (.getMessage e))})))

(defn ensure-number [n]
  (when-not (or (nil? n)
                (number? n))
    (throw (ex-info "Not a number" {:n n})))
  n)

(defn column-index
  "Returns the column index for a given column-name"
  [columns column-name]
  (let [idx (first
             (keep-indexed #(when (= column-name (get %2 "columnName")) %1) columns))]
    (if (nil? idx)
      (throw (ex-info "Column not found" {:column-name column-name
                                          :columns columns}))
      idx)))

(defn column-type
  "Lookup the type of a column"
  [columns column-name]
  (let [idx (column-index columns column-name)]
    (get-in columns [idx "type"])))

(defn update-column [columns column-name f & args]
  (let [idx (column-index columns column-name)]
    (apply update columns idx f args)))

(defn error-strategy [op-spec]
  (get op-spec "onError"))

(defn args [op-spec]
  (get op-spec "args"))

(defn derivation-column-name
  [i]
  {:pre [(int? i)]}
  (format "d%s" i))

(defn is-derived? [column-name]
  (boolean (re-matches #"d\d+$" column-name)))

(defn next-column-index [columns]
  (let [nums (->> columns
                  (map #(get % "columnName"))
                  (filter is-derived?)
                  (map #(subs % 1))
                  (map #(Long/parseLong %)))]
    (if (empty? nums)
      1
      (inc (apply max nums)))))

(defn next-column-name [columns]
  (derivation-column-name (next-column-index columns)))

(defn diff-columns [previous-columns next-columns]
  (let [previous-columns (util/index-by "columnName" previous-columns)
        next-columns (util/index-by "columnName" next-columns)
        all-column-names (set/union (set (keys previous-columns))
                                    (set (keys next-columns)))
        changed-columns (for [column-name all-column-names
                              :let [before (get previous-columns column-name)
                                    after (get next-columns column-name)]
                              :when (not= before after)]
                          {"before" before "after" after})]
    (reduce (fn [diff {:strs [before after]}]
              (let [column-name (or (get before "columnName")
                                    (get after "columnName"))]
                (assoc diff column-name {"before" before "after" after})))
            {}
            changed-columns)))

(defn datagroup-by-column [data-groups column-name]
  (->> data-groups
       (filter (fn [dg] (seq (filter #(= column-name (get % "columnName")) (:columns dg)))))
       first))

(defn ensure-one-data-group-related [transformation column-names data-groups]
  (when (> (->> column-names
                (map (partial datagroup-by-column data-groups))
                set
                count)
           1)
    (throw (ex-info "Failed to transform. Transformation can't have columns from more than one data-group specified"
                    {:transformation transformation}))))

(defn data-group-by-op [transformation data-groups]
  (let [columns-in-transformation (columns-used (w/keywordize-keys transformation) (reduce into [] (map :columns data-groups)))
        _  (ensure-one-data-group-related transformation columns-in-transformation data-groups)
        column-name (first columns-in-transformation)]
    (datagroup-by-column data-groups column-name)))

(defn try-apply-operation-2
  "adapt try-apply-operation to work with data-groups:
  1. find data-group related to transformation
  2. grab all data-group columns
  3. apply-operation
  4. separate transformation data-group columns from the others"
  [{:keys [tenant-conn] :as deps}
   {:keys [transformation data-groups data-group dataset-id main-op]}]
  (let [data-group (or data-group (data-group-by-op transformation data-groups))
        source-table (:table-name data-group)
        other-dgs-columns (reduce (fn [c dg] (if (= (:id dg) (:id data-group))
                                               c (into c (:columns dg)))) [] data-groups)
        previous-columns (into (vec (:columns data-group)) other-dgs-columns)
        {:keys [success? message execution-log error-data data-groups-to-be-created] :as res}
        (try-apply-operation deps source-table previous-columns (assoc transformation
                                                                       :main-op main-op
                                                                       :dataset-id dataset-id))
        columns (let [other-dgs-columns-set (set other-dgs-columns)]
                  (reduce (fn [container column]
                            (if (contains? other-dgs-columns-set column)
                              container
                              (conj container column))) [] (:columns res)))]
    (when-not success?
      (log/errorf "Failed to transform: %s, columns: %s, execution-log: %s, data: %s" message columns execution-log error-data)
      (throw (ex-info (or message "") {:transformation-result (select-keys res [:success? :message :columns :execution-log])})))
    {:data-groups-to-be-created (or data-groups-to-be-created [])
     :columns columns
     :data-group data-group}))

(defn adapt [data-groups-to-be-created data-groups]
  (let [max-group-order (apply max (conj (map :group-order data-groups) 1000))]
    (map-indexed (fn [i item]
                   (let [idx        (inc (+ i max-group-order))
                         group-name (str (:group-name item) " [merge]")]
                                                 (-> item
                                                     (assoc :group-order idx)
                                                     (assoc :group-id idx)
                                                     (assoc :group-name group-name)
                                                     (update :columns (fn [cols]
                                                                        (mapv #(assoc % "groupId" idx "groupName" group-name) cols))))))
                                             data-groups-to-be-created)))

(defn post-try-apply-operation-2
  "
  1. create dataset-version(-2) with new transformation in transformations
  2. create new data-groups if tx result need it. eg: merge-datasets
  3. create new data-group with new columns related to the tx
  4. create all other data-groups to point new dataset-version-id
  5. touch dataset
  "
  [{:keys [tenant-conn claims] :as deps}
   {:keys [job-execution-id transformation data-groups]}
   {:keys [columns data-groups-to-be-created data-group dataset-version]}]
  (let [new-dataset-version-id (str (util/squuid))]
    (db.dataset-version/new-dataset-version-2 tenant-conn {:id               new-dataset-version-id
                                                           :dataset-id       (:dataset-id transformation)
                                                           :job-execution-id job-execution-id
                                                           :version          (inc (:version dataset-version))
                                                           :author           claims
                                                           :transformations  (w/keywordize-keys
                                                                              (conj (vec (:transformations dataset-version))
                                                                                    (assoc transformation
                                                                                           "created" (Instant/ofEpochMilli (System/currentTimeMillis))
                                                                                           "changedColumns" (diff-columns (:columns data-group)
                                                                                                                          columns))))})
    (when (seq data-groups-to-be-created)
      (doseq [dg (adapt data-groups-to-be-created data-groups)]
        (db.data-group/new-data-group tenant-conn
                                      (merge
                                       dg
                                       {:id                 (util/squuid)
                                        :dataset-version-id new-dataset-version-id}))))
    (db.data-group/new-data-group tenant-conn
                                  (merge
                                   (select-keys data-group
                                                [:imported-table-name :table-name :group-id :group-name :repeatable :group-order])
                                   {:id                 (util/squuid)
                                    :dataset-version-id new-dataset-version-id
                                    :columns            columns}))
    (doseq [dg    (db.data-group/list-data-groups-by-dataset-version-id tenant-conn
                                                                        {:dataset-version-id (:id dataset-version)})
            :when (not= (:id dg) (:id data-group))]
      (db.data-group/new-data-group tenant-conn (-> dg
                                                    (assoc :dataset-version-id new-dataset-version-id :id (util/squuid))
                                                    (update :columns vec))))
    (db.transformation/touch-dataset tenant-conn {:id (:dataset-id transformation)})))

(defn execute-transformation-2
  [{:keys [tenant-conn claims] :as deps} dataset-id job-execution-id transformation]
  (let [transformation (assoc transformation :dataset-id dataset-id)
        dataset-version (db.dataset-version/latest-dataset-version-2-by-dataset-id tenant-conn {:dataset-id dataset-id})
        data-groups (db.data-group/list-data-groups-by-dataset-version-id tenant-conn {:dataset-version-id (:id dataset-version)})
        tx-data {:data-groups data-groups
                 :job-execution-id job-execution-id
                 :transformation transformation
                 :dataset-id dataset-id
                 :main-op :transformation}
        {:keys [columns data-groups-to-be-created data-group]}
        (try-apply-operation-2 deps tx-data)]
    (post-try-apply-operation-2 deps tx-data
                                {:columns columns
                                 :dataset-version dataset-version
                                 :data-group data-group
                                 :data-groups-to-be-created data-groups-to-be-created})))

(defn execute-transformation-1
  [{:keys [tenant-conn] :as deps} dataset-id job-execution-id transformation]
  (let [dataset-version (db.transformation/latest-dataset-version-by-dataset-id tenant-conn {:dataset-id dataset-id})
        previous-columns (vec (:columns dataset-version))
        source-table (:table-name dataset-version)]
    (let [{:keys [success? message columns execution-log error-data]}
          (try-apply-operation deps source-table previous-columns (assoc transformation
                                                                         :dataset-id dataset-id
                                                                         :main-op :transformation))]
      (when-not success?
        (log/errorf "Failed to transform: %s, columns: %s, execution-log: %s, data: %s" message columns execution-log error-data)
        (throw (ex-info (or message "") {})))
      (let [new-dataset-version-id (str (util/squuid))]
        (db.transformation/clear-dataset-version-data-table tenant-conn {:id (:id dataset-version)})
        (let [next-dataset-version {:id new-dataset-version-id
                                    :dataset-id dataset-id
                                    :job-execution-id job-execution-id
                                    :table-name source-table
                                    :imported-table-name (:imported-table-name dataset-version)
                                    :version (inc (:version dataset-version))
                                    :transformations (w/keywordize-keys
                                                      (conj (vec (:transformations dataset-version))
                                                            (assoc transformation
                                                                   "created" (Instant/ofEpochMilli (System/currentTimeMillis))
                                                                   "changedColumns" (diff-columns previous-columns
                                                                                                  columns))))
                                    :columns (w/keywordize-keys columns)}]
          (db.dataset-version/new-dataset-version tenant-conn next-dataset-version)
          (db.transformation/touch-dataset tenant-conn {:id dataset-id}))))))

(defn execute-transformation
  [{:keys [tenant-conn] :as deps} dataset-id job-execution-id transformation]
  (if (and (get (env/all tenant-conn) "data-groups")
           (or (contains? s.transformation/single-column-transformations (get transformation "op"))
               (contains? s.transformation/multiple-column-transformations (get transformation "op"))))
    (execute-transformation-2 deps dataset-id job-execution-id transformation)
    (execute-transformation-1 deps dataset-id job-execution-id transformation)))

(defn- apply-undo [{:keys [tenant-conn] :as deps} dataset-id job-execution-id current-dataset-version]
  (let [imported-table-name (:imported-table-name current-dataset-version)
        previous-table-name (:table-name current-dataset-version)
        initial-columns (vec (:columns (db.transformation/initial-dataset-version-to-update-by-dataset-id
                                        tenant-conn
                                        {:dataset-id dataset-id})))
        table-name (util/gen-table-name "ds")]
    (db.transformation/copy-table tenant-conn
                {:source-table imported-table-name
                 :dest-table table-name}
                {}
                {:transaction? false})
    (loop [transformations (butlast (:transformations current-dataset-version))
           columns initial-columns
           full-execution-log [] ;; TODO: remove not in use!
           tx-index 0]
      (if (empty? transformations)
        (let [new-dataset-version-id (str (util/squuid))]
          (db.transformation/clear-dataset-version-data-table tenant-conn {:id (:id current-dataset-version)})
          (let [next-dataset-version {:id new-dataset-version-id
                                      :dataset-id dataset-id
                                      :job-execution-id job-execution-id
                                      :table-name table-name
                                      :imported-table-name (:imported-table-name current-dataset-version)
                                      :version (inc (:version current-dataset-version))
                                      :transformations (w/keywordize-keys
                                                        (vec
                                                         (butlast
                                                          (:transformations current-dataset-version))))
                                      :columns (w/keywordize-keys columns)}]
            (db.dataset-version/new-dataset-version tenant-conn
                                 next-dataset-version)
            (db.transformation/touch-dataset tenant-conn {:id dataset-id})
            (db.transformation/drop-table tenant-conn {:table-name previous-table-name})))
        (let [transformation (assoc (first transformations) :dataset-id dataset-id)
              {:keys [success? message columns execution-log] :as transformation-result}
              (try-apply-operation deps table-name columns (assoc transformation
                                                                  :dataset-id dataset-id
                                                                  :main-op :undo))]
          (if success?
            (recur (rest transformations) columns (into full-execution-log execution-log) (inc tx-index))
            (do
              (log/info (str "Unsuccessful undo of dataset: " dataset-id))
              (log/debug message)
              (log/debug "Job executionid: " job-execution-id)
              (throw (ex-info (str "Failed to undo transformation index:" tx-index ". Tx message:" message) {:transformation-result transformation-result
                                                                                     :transformation transformation})))))))))

(defn copy-tables-2 [tenant-conn data-groups]
  (reduce (fn [c data-group]
         (let [imported-table-name (:imported-table-name data-group)
               table-name (util/gen-table-name "ds")]
           (db.transformation/copy-table tenant-conn
                                         {:source-table imported-table-name
                                          :dest-table   table-name}
                                         {}
                                         {:transaction? false})

           (assoc c (:group-id data-group) {:table-name table-name :previous-table-name (:table-name data-group)
                                            :imported-table-name (:imported-table-name data-group)}))
         ) {} data-groups))

(defn apply-undo-2 [{:keys [tenant-conn claims txs] :as deps} dataset-id job-execution-id current-dataset-version]
  (let [tables-dict     (->> (db.data-group/list-data-groups-by-dataset-version-id tenant-conn {:dataset-version-id (:id current-dataset-version)})
                             (copy-tables-2 tenant-conn))
        initial-dataset-version (db.transformation/initial-dataset-version-2-to-update-by-dataset-id
                                 tenant-conn
                                 {:dataset-id dataset-id})
        initial-data-groups     (->> (db.data-group/list-data-groups-by-dataset-version-id tenant-conn {:dataset-version-id (:id initial-dataset-version)})
                                     (map (fn [dg] (merge dg (get tables-dict (:group-id dg))))))]
    (log/error :initial-dataset-version initial-dataset-version)
    (log/error :initial-data-groups initial-data-groups)
    (log/error :txs txs)
    (loop [data-groups     initial-data-groups
           transformations (if txs
                             txs
                             (butlast (:transformations current-dataset-version)))
           tx-index        0]
      (if (empty? transformations)
        (let [new-dataset-version-id (str (util/squuid))]
          (db.dataset-version/new-dataset-version-2 tenant-conn {:id               new-dataset-version-id
                                                                 :dataset-id       dataset-id
                                                                 :job-execution-id job-execution-id
                                                                 :version          (inc (:version current-dataset-version))
                                                                 :author           claims
                                                                 :transformations  (w/keywordize-keys (vec
                                                                                                       (if txs
                                                                                                         txs
                                                                                                         (butlast (:transformations current-dataset-version)))))})
          (doseq [data-group data-groups]
            (let [new-data-group-id (str (util/squuid))]
              (db.transformation/clear-data-group-data-table tenant-conn {:id (:id data-group)})
              (db.data-group/new-data-group tenant-conn (assoc data-group
                                                               :id                  new-data-group-id
                                                               :dataset-version-id  new-dataset-version-id
                                                               :version             (inc (:version current-dataset-version))
                                                               :columns             (vec (w/keywordize-keys (:columns data-group)))))
              (when-not (= MERGE-DATASET (:imported-table-name data-group))
                (db.transformation/drop-table tenant-conn {:table-name (:previous-table-name data-group)}))))
          (db.transformation/touch-dataset tenant-conn {:id dataset-id}))
        (let [transformation (assoc (first transformations) :dataset-id dataset-id)
              {:keys [data-groups-to-be-created data-group columns]}
              (try
                (try-apply-operation-2 deps {:transformation transformation
                                             :data-groups data-groups
                                             :dataset-id dataset-id
                                             :main-op :undo})
                (catch ExceptionInfo e
                  (do
                    (log/info (str "Unsuccessful undo of dataset: " dataset-id))
                    (log/debug (->  (ex-data e) :transformation-result :message))
                    (log/debug "Job executionid: " job-execution-id)
                    (throw (ex-info (str "Failed to undo transformation index:" tx-index ". Tx message:" (.getMessage e))
                                    {:transformation-result (:transformation-result (ex-data e))
                                     :transformation        transformation})))))
              data-groups-to-be-created (adapt data-groups-to-be-created data-groups)]
          (recur (reduce (fn [c dg]
                           (if (= (:id dg) (:id data-group ))
                             (conj c (assoc dg :columns columns))
                             (conj c dg)))
                         data-groups-to-be-created data-groups)
                 (rest transformations)
                 (inc tx-index)))))))

(defn execute-undo [{:keys [tenant-conn] :as deps} dataset-id job-execution-id]
  (if (get (env/all tenant-conn) "data-groups")
    (let [current-dataset-version (db.dataset-version/latest-dataset-version-2-by-dataset-id tenant-conn
                                                                                          {:dataset-id dataset-id})]
      (when (not= (:version current-dataset-version) 1)
        (apply-undo-2 deps
                      dataset-id
                      job-execution-id
                      current-dataset-version)))
   (let [current-dataset-version (db.transformation/latest-dataset-version-by-dataset-id tenant-conn
                                                                                         {:dataset-id dataset-id})]
     (when (not= (:version current-dataset-version) 1)
       (apply-undo deps
                   dataset-id
                   job-execution-id
                   current-dataset-version)))))

(defmulti adapt-transformation
  (fn [op-spec older-columns new-columns]
    (get op-spec "op")))

(defmethod adapt-transformation :default
  [op-spec older-columns new-columns]
  op-spec)

(defn apply-dataset-transformations-on-table
  "no transactional thus we can discard the temporary table we are working with"
  [conn caddisfly dataset-id transformations table-name new-columns old-columns]
  (loop [transformations transformations
         columns         new-columns
         applied-txs     []]
    (if-let [transformation-original (first transformations)]
      (let [transformation-adapted       (adapt-transformation transformation-original old-columns columns)
            avoid-tranformation? (let [t (w/keywordize-keys transformation-adapted)]
                                   (and
                                    (avoidable-if-missing? t)
                                    ((complement set/subset?)
                                     (set (columns-used t columns))
                                     (set (map #(get % "columnName") columns)))))]
        (if avoid-tranformation?
          (recur (rest transformations) columns  applied-txs)
          (let [op (try-apply-operation {:tenant-conn conn :caddisfly caddisfly} table-name columns (assoc transformation-adapted :dataset-id dataset-id :main-op :update))]
            (when-not (:success? op)
              (throw
               (ex-info (format "Failed to update due to transformation mismatch: %s . TX: %s" (:message op) transformation-original) {})))
            (let [applied-txs (conj applied-txs
                                    (assoc transformation-original "changedColumns"
                                           (diff-columns columns (:columns op))))]
              (db.job-execution/vacuum-table conn {:table-name table-name})
              (recur (rest transformations) (:columns op)  applied-txs)))))
      {:columns             (w/keywordize-keys columns)
       :transformations     (w/keywordize-keys (vec applied-txs))})))

(defn apply-dataset-transformations-on-table-2
  "no transactional thus we can discard the temporary table we are working with"
  [conn caddisfly dataset-id transformations group-table-names new-columns old-columns]
  (loop [transformations transformations
         data-groups    (->> new-columns
                             (reduce (fn [c co]
                                       (update c {:group-id (get co "groupId")
                                                  :group-name (get co "groupName")}  conj co)) {})
                             (map-indexed (fn [idx [group columns]]
                                            (assoc group
                                                   :group-order idx
                                                   :columns (reverse columns)
                                                   :table-name (get group-table-names (:group-id group))))))
         applied-txs     []]
    (if-let [transformation-original (first transformations)]
      (let [columns (reduce into [] (map :columns data-groups))
            transformation-adapted       (adapt-transformation transformation-original old-columns columns)
            data-group (data-group-by-op transformation-adapted data-groups)
            avoid-tranformation? (let [t (w/keywordize-keys transformation-adapted)]
                                   (and
                                    (avoidable-if-missing? t)
                                    ((complement set/subset?)
                                     (set (columns-used t columns))
                                     (set (map #(get % "columnName") columns)))))]
        (if avoid-tranformation?
          (recur (rest transformations) data-groups applied-txs)
          (let [{:keys [data-groups-to-be-created columns data-group] :as op}
                (try
                  (try-apply-operation-2 {:tenant-conn conn :caddisfly caddisfly}
                                         {:data-group data-group
                                          :data-groups data-groups
                                          :dataset-id dataset-id
                                          :main-op :update
                                          :transformation (assoc transformation-adapted :dataset-id dataset-id)})
                  (catch ExceptionInfo e
                    (do
                      (log/error e)
                      (throw
                       (ex-info (format "Failed to update due to transformation mismatch: %s . TX: %s"
                                        (-> e ex-data :transformation-result :message) transformation-original) {})))))
                data-groups-to-be-created (adapt data-groups-to-be-created data-groups)]
            (let [applied-txs (conj applied-txs
                                    (assoc transformation-original "changedColumns"
                                           (diff-columns (:columns data-group)  columns)))]
              (db.job-execution/vacuum-table conn {:table-name (:table-name data-group)})
              (recur (rest transformations)
                     (reduce (fn [c dg]
                           (if (= (:group-id dg) (:group-id data-group))
                             (conj c (assoc dg :columns columns))
                             (conj c dg)))
                         data-groups-to-be-created data-groups)
                     applied-txs)))))
      {:data-groups data-groups
       :transformations  (w/keywordize-keys (vec applied-txs))})))

(defn column-title-error? [column-title columns]
  (when (not-empty (filter #(= column-title (get % "title")) columns))
    {:success? false
     :message  (format "In this dataset there's already a column with this name: %s. Please choose another non existing name" column-title)}))
