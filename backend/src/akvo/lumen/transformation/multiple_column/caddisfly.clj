(ns akvo.lumen.transformation.multiple-column.caddisfly
  (:require [akvo.lumen.dataset.utils :as u]
            [akvo.lumen.transformation.engine :as engine]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.walk :refer (keywordize-keys)]
            [hugsql.core :as hugsql]))


(hugsql/def-db-fns "akvo/lumen/transformation/derive.sql")

(hugsql/def-db-fns "akvo/lumen/transformation/engine.sql")

(hugsql/def-db-fns "akvo/lumen/transformation/caddisfly.sql")


(def parse-json #(json/parse-string (slurp (io/resource %)) keyword))

(def schemas (->> (:tests (parse-json "./caddisfly/tests-schema.json"))
                  (reduce #(assoc % (:uuid %2) %2) {})))

(def has-image-schema-example (get schemas "53a1649a-be67-4a13-8cba-1b7db640037c"))

(defn caddisfly-test-results [cad-val cad-schema]
  (log/error :image? (:hasImage cad-schema) (:image cad-val) cad-val cad-schema)
  (let [result (:result cad-val)]
    (if (:hasImage cad-schema)
      (vec (cons {:value (:image cad-val)} result))
      result)))
;;=> [{:id 1, :name "Fluoride", :unit "ppm", :value "> 1.80"}]

(defn extract-caddisfly-column
  [column next-column-index]
  (if-let [caddisflyResourceUuid (:subtypeId column)]
    (let [column (dissoc column :subtypeId)
          caddisfly-schema (get schemas caddisflyResourceUuid)]
      (->> (reduce #(conj % (assoc column :title (str (:title column) "|" (:name %2) "|" (:unit %2))))
                   (if (:hasImage caddisfly-schema)
                     [(assoc column :title (str (:title column) "| Image" ))]
                     [])
                   (:results caddisfly-schema))
           (map #(let [id (engine/int->derivation-column-name %)]
                   (assoc %2 :columnName id :id id))
                (filter #(>= % next-column-index) (range)))))
    (throw (ex-info "this column doesn't have a caddisflyResourceUuid currently associated!" {:message {:possible-reason "maybe you don't update the dataset!?"}}))))


(defn update-row [conn table-name row-id vals-map]
  (let [r (string/join "," (doall (map (fn [[k v]]
                                         (str (name k) "='" v "'::TEXT")) vals-map)))
        sql (str  "update " table-name " SET "  r " where rnum=" row-id)]
    (jdbc/execute! conn sql)))

(defn col-name [op-spec]
  (get (engine/args op-spec) "columnName"))

(defn set-cells-values! [conn opts data]
  (->> data
       (map (fn [[i v]] (set-cell-value conn (merge {:value v :rnum i} opts))))
       doall))

(defn apply-operation 
  [tenant-conn table-name columns {:keys [selectedColumn extractImage] :as args} onError]
  (jdbc/with-db-transaction [conn tenant-conn]
    (let [;;column-name     (col-name op-spec)
          ;;column          (keywordize-keys (u/find-column columns column-name))
          next-column-index (engine/next-column-index columns)
          column-idx      (engine/column-index columns (:columnName selectedColumn))
          new-columns     (extract-caddisfly-column selectedColumn next-column-index)
          cad-schema      (get schemas (:subtypeId selectedColumn))
          ]
      #_(log/debug :new-columns new-columns)
      #_(doseq [c new-columns]
        (log/debug "persist new-column " c)
        (add-column conn {:table-name      table-name
                          :column-type     "text" ;; TODO: do we need to support more types?
                          :new-column-name (:id c)}))

      #_(->> (caddisfly-data conn {:table-name table-name :column-name column-name})

           (map (fn [m]
                  (let [cad-results (or (caddisfly-test-results (json/parse-string ((keyword column-name) m) keyword) cad-schema)
                                        (repeat nil))
                        update-vals (->> (map
                                          (fn [new-column-name new-column-val]
                                            [(keyword new-column-name) new-column-val])
                                          (map :id new-columns) (map :value cad-results))
                                         (reduce #(apply assoc % %2) {}))]
                    (update-row conn table-name (:rnum m) update-vals))))
           doall)
      #_(delete-column conn {:table-name table-name :column-name column-name})
      #_{:success?      true
       :execution-log [(format "Extract caddisfly column %s" column-name)]
       :columns
       (into (into (vec (take column-idx columns)) ;; same approach as delete column
                   (drop (inc column-idx) columns))
             new-columns)}
      {})))



