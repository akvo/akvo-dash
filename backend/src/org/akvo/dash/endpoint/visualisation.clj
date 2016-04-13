(ns org.akvo.dash.endpoint.visualisation
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.pprint :refer [pprint]]
   [cheshire.core :as json]
   [compojure.core :refer :all]
   [hugsql.core :as hugsql]
   [org.akvo.dash.component.tenants :refer [connection]]
   [org.akvo.dash.endpoint.util :refer [rr squuid str->uuid]]))


(hugsql/def-db-fns "org/akvo/dash/endpoint/visualisation.sql")

(defn endpoint
  ""
  [{lord :lord :as config}]

  (context "/visualisations" []

    (GET "/" []
      (fn [{tenant :tenant :as request}]
        (rr (all-visualisations (connection lord
                                            tenant)))))
    (POST "/" []
      (fn [{tenant :tenant :as request}]
        (try
          (let [resp (first (insert-visualisation
                             (connection lord tenant)
                             {:id     (squuid)
                              :name   (get-in request [:body "name"])
                              :spec   (get-in request [:body "spec"])
                              :author {:user "Bob"}}))]
            (rr (dissoc resp :author)))
          (catch Exception e
            (pprint e)
            (pprint (.getNextException e))
            (rr {:error e})))))

    (context "/:id" [id]

      (GET "/" []
        (fn [{tenant :tenant :as request}]
          (rr (dissoc (visualisation-by-id (connection lord tenant)
                                           {:id id})
                      :author)))))))
