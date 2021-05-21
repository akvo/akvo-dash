(ns akvo.lumen.lib.data-groups-adapter
  (:require [akvo.lumen.db.data-source :as db.data-source]
            [akvo.lumen.db.dataset :as db.dataset]
            [akvo.lumen.db.transformation :as db.transformation]
            [akvo.lumen.db.dataset-version :as db.dataset-version]
            [akvo.lumen.lib.import.common :as common]
            [akvo.lumen.lib.transformation.engine :as engine]
            [akvo.lumen.lib.import.data-groups :as i.data-groups]
            [akvo.lumen.postgres :as postgres]
            [akvo.lumen.protocols :as p]
            [cheshire.core :as json]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [clojure.walk :as w]
            [amazonica.aws.s3 :as s3]
            [amazonica.aws.s3transfer :as s3t]
            [clojure.data.xml :as xml]
            [clojure.set :as set]
            [akvo.lumen.lib.transformation-test :as tt]
            [hugsql.core :as hugsql]))

(hugsql/def-db-fns "akvo/lumen/lib/adapter.sql")

(defn adapter [conn rows columns job-execution-id dataset-id claims]
  (let [_ (log/debug :columns-dsv2 columns)
        {:keys [columns group-table-names]} (i.data-groups/adapt-columns columns)
        columns (if-let [instance_id (first (filter #(= "instance_id" (get % :columnName))columns))]
                  (reduce (fn [c groupId]
                            (conj c (assoc instance_id
                                           :groupId groupId
                                           :groupName groupId))
                            ) columns (disj (set (keys (group-by :groupId columns))) "metadata"))
                  columns)
        group-cols (group-by :groupId columns)]
    (log/debug :group-cols group-cols)
    (doseq [[groupId cols] group-cols]
      (postgres/create-dataset-table conn (get group-table-names groupId) cols))
    (doseq [response (take common/rows-limit rows)]
      (log/debug :response-dsv2 response )
      (doseq [[groupId iterations] response]
        (let [table-name (get group-table-names groupId)]
          (log/debug :its iterations)
          (jdbc/insert-multi! conn table-name (mapv postgres/coerce-to-sql iterations)))))
    (postgres/create-data-group-foreign-keys conn group-table-names)
    (i.data-groups/successful-execution conn job-execution-id dataset-id group-table-names columns claims)))

(defn csv? [importer-type ]
  (contains? #{"LINK" "CSV" "DATA_FILE" "clj"} importer-type))

(defn adapter-value [k t x]
  (log/debug :k k :adapter-value t x)
  (if (= "rnum" k)
    x
    (case t
      "text" x
      "number" x
      "date" (java.time.Instant/ofEpochMilli x)
      "geoshape" (postgres/val->geometry-pgobj x)
      "geopoint" (postgres/val->geometry-pgobj x)
      "multiple" x
      "option" x)))

(defn adapter-rows [tenant-conn data-source dataset-id]
  (let [import-type (-> data-source (get-in [:spec "source" "kind"])) ;;"DATA_FILE"
        {:keys [imported-table-name columns] :as dsv1}(db.transformation/initial-dataset-version-to-update-by-dataset-id
                                                       tenant-conn
                                                       {:dataset-id dataset-id})
        dict-type (reduce #(assoc % (or
                                     (get  %2 "columnName")
                                     (get  %2 :columnName))
                                  (or
                                   (get  %2 "type")
                                   (get  %2 :type))) {} columns)

        column-group-dict (reduce (fn [c co]
                                    (assoc c  (get co "columnName") (get co "groupId") )
                                    ){} columns)]
    (log/debug column-group-dict)

    (->> (jdbc/query tenant-conn [(format "SELECT * FROM %s ORDER BY rnum" imported-table-name)])
         (map (fn [x]
                (if (csv? import-type)
                  (let [x2 (reduce (fn [c [k v]]
                                     (assoc c k (adapter-value k (get dict-type k) v))
                                     ) {} (w/stringify-keys x))]
                    {"main" [x2]})

                  (let [it (w/stringify-keys x)]
                    (->> (reduce (fn [c [k v]]
                                   (if-let [group-id (column-group-dict k)]
                                     (update c (column-group-dict k) (fn [x]
                                                                       (let [v2 (adapter-value k (get dict-type k) v)]
                                                                         (if x
                                                                           (assoc x k v2)
                                                                           {k v2}
                                                                           ))))
                                     c)
                                   ){} it)
                         (reduce (fn [c [k v]]
                                   (assoc c k [(merge {"instance_id" (get it "instance_id")} v)])) {})))))))))

(defn adapter-claims [data-source]
  (let [email (get-in data-source [:spec "source" "email"])]
    {"email" email "name" email}))

(defn adapter-data-source [tenant-conn dataset-id]
  (db.data-source/db-data-source-by-dataset-id tenant-conn {:dataset-id dataset-id}))

(defn adapt-columns-group [jwt-token flow-api tenant-conn dataset-id columns]
  (let [spec (merge
              (:source (akvo.lumen.db.dataset/dataset-by-id tenant-conn {:id dataset-id}))
              {"token" jwt-token})]
    (if (= "AKVO_FLOW" (get spec "kind"))
      (let [importer ((get-method common/datagroups-importer "AKVO_FLOW")
                      spec {:flow-api flow-api})
            flow-columns(p/columns importer)]
        (mapv (fn [c]
                (if-let [flow-col (first (filter (fn [fc]
                                                   (= (:columnName c) (:id fc))) flow-columns ))]
                  (merge c (select-keys flow-col [:groupName :groupId]))
                  (throw (ex-info "no flow-col" {:flow-columns flow-columns
                                                 :c c} ))
                  )
                ) columns)
        )
      columns)))

(defn merge-tx-dsv-ordered-by-date [conn]
  (->> (all-merge-dsv-bis conn)
       (mapv (fn [dsv]
               (-> (select-keys dsv [:id :created :dataset_id :version])
                   (assoc  :transformations (seq (filter (fn [t]
                                                           (= "core/merge-datasets" (get t "op"))
                                                           )(:transformations dsv)))))))
       (group-by :dataset_id)
       (reduce (fn [xx [dataset_id dsvs]]
                 (into xx (let [txs (vec (reduce
                                          (fn [c dsv]
                                            (reduce  (fn [cc tx]
                                                       (if-not (contains? cc tx)
                                                         (conj cc (with-meta tx dsv))
                                                         cc))  c (:transformations dsv)))
                                          #{} (sort-by :version dsvs)))]
                            (mapv (fn [tx]
                                    (merge (select-keys (meta tx) [:created :dataset_id :version :id])
                                           {:source-dataset-id (get-in tx ["args" "source" "datasetId"])})
                                    ) txs)))) [])
       (sort-by :created)
       (mapv (fn [x]
               (assoc x :created (java.time.Instant/ofEpochMilli (:created x)))))))

(defn merged-instersections [conn]
  (let [data (reduce (fn [c d]
                       (-> c
                           (update :sources conj (:source-dataset-id d))
                           (update :targets conj (:dataset_id d)))
                       ){:sources #{}
                         :targets #{}}
                     (merge-tx-dsv-ordered-by-date conn))]
    (set/intersection (:targets data) (:sources data))))

(defn create-dsv2 [tenant-conn dataset-id]
  (let [initial-dataset-version (db.transformation/initial-dataset-version-to-update-by-dataset-id tenant-conn {:dataset-id dataset-id})
        ]
    (let [data-source (adapter-data-source tenant-conn dataset-id)
          job-execution-id (:job_execution_id data-source)
          claims (adapter-claims data-source)
          rows (adapter-rows tenant-conn data-source dataset-id)
          columns (->> (:columns initial-dataset-version)
                       (w/keywordize-keys)
                       (adapt-columns-group (dev/jwt-token) (:akvo.lumen.component.flow/api (dev/isystem)) tenant-conn dataset-id))]
      {:res (adapter tenant-conn rows columns job-execution-id dataset-id claims)
       :claims claims
       :job-execution-id job-execution-id}
      ))
  )

(defn apply-dsv2-txs [tenant-conn dataset-id job-execution-id claims initial-dataset-version]
  (let [latest-dataset-version (db.transformation/latest-dataset-version-by-dataset-id tenant-conn {:dataset-id dataset-id})]
    (if (= (:version initial-dataset-version) (:version latest-dataset-version))
      :no-tx
      (let [current-dataset-version (db.dataset-version/latest-dataset-version-2-by-dataset-id tenant-conn {:dataset-id dataset-id})]
        (engine/apply-undo-2 {:tenant-conn tenant-conn
                              :claims claims
                              :txs (:transformations latest-dataset-version)

                              }
                             dataset-id
                             job-execution-id
                             current-dataset-version)))))

(comment

  (merge-tx-dsv-ordered-by-date (dev/db-conn "t2"))

  (merged-instersections (dev/db-conn "t2"))

  #{"V2 Agago Schools merged" "Schools 2021: form 5 Monitoring"}
  #{"6065a59c-3846-4216-a6e5-182441fdfda6" "605c426b-6952-41dd-9fa4-1ef1b7d42760"}
  )

(comment

  (let [tenant-conn (dev/db-conn)
        all-ds (db-all-datasets tenant-conn)]
    (mapv #(create-dsv2 tenant-conn (:id %)) all-ds))

;;  res

 (let [tenant-conn (dev/db-conn)
       all-ds (db-all-datasets tenant-conn)]
   (->> all-ds
        (reduce (fn [c ds]
                  (into c (mapv #(merge (w/keywordize-keys %) {:target-dataset-title (:title ds)
                                                               :target-dataset-id (:id ds)}
                                        {:claims (adapter-claims ds)})
                                (:transformations (db-datasets-by-id tenant-conn {:id (:id ds)})))))
                [])

        (sort-by :created)
        (mapv (fn [tx] (let [body (select-keys tx [:args :op :onError])]
                         {:deps {:tenant-conn nil  :caddisfly nil  :claims (:claims tx)}
                          :dataset-id (:target-dataset-id tx)
                          :command {:type :transformation
                                    :transformation (w/stringify-keys body)}}
                         )))

    ;;    (mapv (fn [x] (dissoc x :changedColumns :args)))
        )

   )

 (def txs *1)
 (mapv (fn [{:keys [deps dataset-id command]}]
         (tt/async-tx-apply (assoc deps :tenant-conn (dev/db-conn)) dataset-id command)
         )
       txs)
 [[:akvo.lumen.lib/ok {:jobExecutionId "609e9238-d521-43b3-8ac5-33fb49ed8164", :datasetId "609e5c9b-207d-4706-9226-89028259704e"} "OK" {:id "609e9238-d521-43b3-8ac5-33fb49ed8164", :status "OK", :error-message nil, :kind "TRANSFORMATION", :dataset-id "609e5c9b-207d-4706-9226-89028259704e", :data-source-id nil}] [:akvo.lumen.lib/ok {:jobExecutionId "609e9238-9bbc-45a9-96eb-a7a64e073443", :datasetId "609e5ca1-0f20-4667-9940-715154f05733"} "OK" {:id "609e9238-9bbc-45a9-96eb-a7a64e073443", :status "OK", :error-message nil, :kind "TRANSFORMATION", :dataset-id "609e5ca1-0f20-4667-9940-715154f05733", :data-source-id nil}] [:akvo.lumen.lib/ok {:jobExecutionId "609e9238-d75b-447c-a3ef-47f164c904ec", :datasetId "609e5c9b-207d-4706-9226-89028259704e"} "OK" {:id "609e9238-d75b-447c-a3ef-47f164c904ec", :status "OK", :error-message nil, :kind "TRANSFORMATION", :dataset-id "609e5c9b-207d-4706-9226-89028259704e", :data-source-id nil}] [:akvo.lumen.lib/ok {:jobExecutionId "609e9239-0561-49bd-9687-a59258acaa6a", :datasetId "609e5ca1-0f20-4667-9940-715154f05733"} "OK" {:id "609e9239-0561-49bd-9687-a59258acaa6a", :status "OK", :error-message nil, :kind "TRANSFORMATION", :dataset-id "609e5ca1-0f20-4667-9940-715154f05733", :data-source-id nil}]]

 )










;; --------------------




















(def aws-creds1 {:access-key ""
                 :secret-key ""
                 :endpoint   "us-west-1"})
(def aws-creds2 {:access-key ""
                 :secret-key ""
                 :endpoint   "us-west-1"})


#_(s3/list-buckets aws-creds1
                   {:client-config {
                                    :path-style-access-enabled false
                                    :chunked-encoding-disabled false
                                    :accelerate-mode-enabled false
                                    :payload-signing-enabled true
                                    :dualstack-enabled true
                                    :force-global-bucket-access-enabled true}})

(def key-pair
  (let [kg (java.security.KeyPairGenerator/getInstance "RSA")]
    (.initialize kg 1024 (java.security.SecureRandom.))
    (.generateKeyPair kg)))

(defn form
  ([flow-instance-id survey-id]
   (try (form aws-creds1 flow-instance-id survey-id)
        (catch Exception e (form aws-creds2 flow-instance-id survey-id)))
   )
  ([creds flow-instance-id survey-id]
   (let [stream (-> (s3/get-object creds :bucket-name (str "akvoflow-" flow-instance-id)
                                   :encryption {:key-pair key-pair}
                                   :key (format "surveys/%s.zip" survey-id))
                    :input-stream

                    (java.util.zip.ZipInputStream.)

                    )]
     (.getNextEntry stream)
     (xml/parse-str (slurp stream)))))

(defn questions [xml-questions]
  (mapv (fn [e]  (-> (select-keys (:attrs e) [:id :order :type])
                     (assoc :name (->  e :content last :content first)))) xml-questions))
(defn groups [xml-form]
  (mapv (fn [e]
          {(-> e :content first :content first)
           (questions (-> e :content next))}) (:content xml-form)))
(comment
  (groups (form "161" "26200002"))
;;=>   [{"SCHOOL BASELINE " [{:id "25160084", :order "1", :type "cascade", :name "Location Details"} {:id "20230028", :order "2", :type "free", :name "Name of The School "} {:id "25250003", :order "3", :type "option", :name "What is the type of school?"} {:id "22200002", :order "4", :type "photo", :name " Take a photo of the school and school sign"} {:id "28080020", :order "5", :type "geo", :name "Take the GPS location at the school"}]}]
  (groups (form "7" "979504001")))
