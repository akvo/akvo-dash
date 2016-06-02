(ns org.akvo.dash.transformation-test
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.test :refer :all]
            [hugsql.core :as hugsql]
            [org.akvo.dash.fixtures :refer (test-conn
                                            test-tenant-spec
                                            migrate-tenant
                                            rollback-tenant)]
            [org.akvo.dash.import :as imp]
            [org.akvo.dash.transformation :as tf]
            [org.akvo.dash.util :refer (squuid)]))

(def ops (vec (json/parse-string (slurp (io/resource "ops.json")))))
(def invalid-op (-> (take 3 ops)
                    vec
                    (update-in [1 "args"] dissoc "parseFormat")))


(hugsql/def-db-fns "org/akvo/dash/job-execution.sql")
(hugsql/def-db-fns "org/akvo/dash/transformation_test.sql")


(defn test-fixture
  [f]
  (rollback-tenant test-tenant-spec)
  (migrate-tenant test-tenant-spec)
  (rollback-test-data test-conn)
  (new-test-table test-conn)
  (new-test-data test-conn)
  (f))

(use-fixtures :once test-fixture)

(deftest op-validation
  (testing "op validation"
    (is (= true (:valid? (tf/validate ops))))
    (let [result (tf/validate invalid-op)]
      (is (= false (:valid? result)))
      (is (= (format "Invalid operation %s" (second invalid-op)) (:message result))))))

(deftest ^:functional test-transformations
  (testing "Transformation application"
    (is (= {:status 400 :body {:message "Dataset not found"}}
           (tf/schedule test-conn "Not-valid-id" []))))
  (testing "Valid log"
    (let [resp (tf/schedule test-conn "ds-1" ops)]
      (is (= 200 (:status resp))))))

(deftest ^:functional test-import-and-transform
  (testing "Import CSV and transform"
    (let [data-source-id (str (squuid))
          job-id (str (squuid))
          data-source-spec {"name" "GDP Test"
                            "source" {"path" (.getAbsolutePath (io/file (io/resource "GDP.csv")))
                                      "kind" "DATA_FILE"
                                      "fileName" "GDP.csv"
                                      "hascolumnheaders" false}}
          t-log [{"op" "core/trim"
                  "args" {"columnName" "c5"}
                  "onError" "fail"}
                 {"op" "core/change-datatype"
                  "args" {"columnName" "c5"
                          "newType" "number"
                          "defaultValue" 0}
                  "onError" "default-value"}]]
      (insert-data-source test-conn {:id data-source-id
                                     :spec (json/generate-string data-source-spec)})
      (insert-job-execution test-conn {:id job-id
                                       :data-source-id data-source-id})
      (imp/do-import test-conn {:file-upload-path "/tmp/akvo/dash"} job-id)
      (let [dataset-id (:dataset_id (dataset-id-by-job-execution-id test-conn {:id job-id}))
            transformation-job (tf/schedule test-conn dataset-id t-log)
            t-job-id (get-in transformation-job [:body :jobExecutionId])]

        (is (= 200  (:status transformation-job)))

        (loop [job (job-execution-status test-conn {:id t-job-id})]
          (if (not= (:status job) "PENDING")
            true
            (recur (job-execution-status test-conn {:id t-job-id}))))

        (is (= "OK" (:status (job-execution-status test-conn {:id t-job-id}))))

        (is (zero? (:c5 (get-val-from-table test-conn
                                            {:rnum 196
                                             :column-name "c5"
                                             :table-name (:table-name (get-table-name test-conn
                                                                                      {:job-id t-job-id}))})))))
      )))
