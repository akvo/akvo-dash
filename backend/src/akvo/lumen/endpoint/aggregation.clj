(ns akvo.lumen.endpoint.aggregation
  (:require [akvo.lumen.component.tenant-manager :refer [connection]]
            [akvo.lumen.http :as http]
            [akvo.lumen.lib.aggregation :as aggregation]
            [cheshire.core :as json]
            [compojure.core :refer :all]
            [integrant.core :as ig])
  (:import [com.fasterxml.jackson.core JsonParseException]))

(defn endpoint [{:keys [tenant-manager]}]
  (context "/api/aggregation" {:keys [tenant query-params] :as request}
    (let-routes [tenant-conn (connection tenant-manager tenant)]
      (GET "/:dataset-id/:visualisation-type" [dataset-id visualisation-type]
        (if-let [query (get query-params "query")]
          (try
            (aggregation/query tenant-conn
                               dataset-id
                               visualisation-type
                               (json/parse-string query))
            (catch JsonParseException e
              (http/bad-request {:message (.getMessage e)})))
          (http/bad-request {:message "No query supplied"}))))))

(defmethod ig/init-key :akvo.lumen.endpoint.aggregation/aggregation  [_ opts]
  (endpoint opts))
