(ns org.akvo.lumen.lib.job-execution-impl
  (:require [hugsql.core :as hugsql]
            [ring.util.response :refer [not-found response status]]))


(hugsql/def-db-fns "org/akvo/lumen/job-execution.sql")


(defn job-status
  "Get the status of a job execution. There are three different states:
  * { \"status\": \"PENDING\", \"jobExecytionId\": <id> }
  * { \"status\": \"FAILED\", \"jobExecutionId\": <id>, \"reason\": <reason> }
  * { \"status\": \"OK\", \"jobExecutionId\": <id>, \"datasetId\": <dataset-id> }
  Returns nil if the job execution does not exist"
  [conn id]
  (if-let [{:keys [dataset_id]} (dataset-id-by-job-execution-id conn {:id id})]
    (response
     {"jobExecutionId" id
      "status" "OK"
      "datasetId" dataset_id})
    (if-let [{:keys [status error-message]} (job-execution-by-id conn {:id id})]
      (-> (response
           {"jobExecutionId" id
            "status" status
            "reason" error-message})
          (status 400))
      (not-found {:error "not-found"}))))
