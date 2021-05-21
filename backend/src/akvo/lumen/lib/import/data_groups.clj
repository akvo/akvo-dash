(ns akvo.lumen.lib.import.data-groups
  (:require [akvo.lumen.lib.import.common :as common]
            [akvo.lumen.postgres :as postgres]
            [akvo.lumen.db.job-execution :as db.job-execution]
            [akvo.lumen.db.dataset :as db.dataset]
            [akvo.lumen.db.dataset-version :as db.dataset-version]
            [akvo.lumen.db.data-group :as db.data-group]
            [akvo.lumen.db.transformation :as db.transformation]
            [akvo.lumen.protocols :as p]
            [akvo.lumen.lib.import.csv]
            [akvo.lumen.lib.env :as env]
            [akvo.lumen.lib.data-group :as lib.data-group]
            [akvo.lumen.lib.import.flow :as i.flow]
            [akvo.lumen.lib.import.csv]
            [akvo.lumen.lib.raster]
            [akvo.lumen.lib :as lib]
            [akvo.lumen.util :as util]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.tools.logging :as log]))

(defn successful-execution [conn job-execution-id dataset-id group-table-names columns claims]
  (let [dataset-version-id (util/squuid)
        grouped-columns (group-by :groupId columns)
        ordered-groups-ids (distinct (map :groupId columns))
        ordered-grouped-columns-list (->> ordered-groups-ids
                                          (filter #(not= % "metadata"))
                                          (map vector (range))
                                          (reduce (fn [c [group-order group-id]]
                                                    (conj c {:group-id group-id
                                                             :columns (get grouped-columns group-id)
                                                             :group-order group-order})) ()))
        ordered-grouped-columns (if (contains? (set ordered-groups-ids) "metadata")
                                  (vec (reverse (conj (map #(update % :group-order inc) ordered-grouped-columns-list)
                                                      {:group-id "metadata"
                                                       :columns (get grouped-columns "metadata")
                                                       :group-order 0})))
                                  (vec (reverse ordered-grouped-columns-list)))]
    (db.dataset-version/new-dataset-version-2 conn
                                              {:id dataset-version-id
                                               :dataset-id dataset-id
                                               :job-execution-id job-execution-id
                                               :author claims
                                               :version 1
                                               :transformations []})
    (doseq [{:keys [columns group-id group-order]} ordered-grouped-columns]
      (let [imported-table-name (util/gen-table-name "imported")
            table-name (get group-table-names group-id)
            instance-id-col (->> "metadata"
                                 grouped-columns
                                 (filter #(= (:id %) "instance_id"))
                                 first)]
        (db.job-execution/clone-data-table conn
                                           {:from-table table-name
                                            :to-table imported-table-name}
                                           {}
                                           {:transaction? false})
        (db.data-group/new-data-group  conn
                                            {:id (util/squuid)
                                             :dataset-version-id dataset-version-id
                                             :imported-table-name imported-table-name
                                             :table-name table-name
                                             :group-id group-id
                                             :group-name (:groupName (first columns) group-id)
                                             :repeatable (:repeatable (first columns) false)
                                             :group-order group-order
                                             :columns (mapv (fn [{:keys [title id columnName type key multipleType multipleId groupName groupId hidden]}]
                                                              {:columnName (or id columnName)
                                                               :direction nil
                                                               :groupId groupId
                                                               :groupName groupName
                                                               :hidden (boolean hidden)
                                                               :key (boolean key)
                                                               :multipleId multipleId
                                                               :multipleType multipleType
                                                               :sort nil
                                                               :title (string/trim title)
                                                               :type type})
                                                            columns)})))
    (lib.data-group/create-view-from-data-groups conn dataset-id)))

(defn adapt-columns [importer-columns]
  (let [columns (map (fn [c] (-> c
                                 (update :groupName (fn [groupName] (or groupName "main")))
                                 (update :groupId (fn [groupId] (or groupId "main")))))
                     importer-columns)]
    (let [group-ids (distinct (map :groupId columns))]
      {:group-table-names (reduce #(assoc % %2 (util/gen-table-name "ds")) {} group-ids)
       :columns columns})))

(defn execute [conn job-execution-id data-source-id dataset-id claims spec import-config]
  (with-open [importer (common/datagroups-importer (get spec "source") (assoc import-config :environment (env/all conn)))]
    (let [rows                                (p/records importer)
          {:keys [columns group-table-names]} (adapt-columns (p/columns importer))]
      (doseq [[groupId cols] (group-by :groupId columns)]
        (postgres/create-dataset-table conn (get group-table-names groupId) cols))
      (doseq [response (take common/rows-limit rows)]
        (doseq [[groupId iterations] response]
          (let [table-name (get group-table-names groupId)]
            (jdbc/insert-multi! conn table-name (mapv postgres/coerce-to-sql iterations)))))
      (postgres/create-data-group-foreign-keys conn group-table-names)
      (successful-execution conn job-execution-id dataset-id group-table-names columns claims))))


