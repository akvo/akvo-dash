(ns org.akvo.dash.endpoint.dataset
  "Dataset..."
  (:require
   [clojure.java.jdbc :as jdbc]
   [clojure.data.csv :as csv]
   [cheshire.core :as json]
   [clj-http.client :as client]
   [clojure.pprint :refer [pprint]]
   [compojure.core :refer :all]
   [hugsql.core :as hugsql]
   [immutant.scheduling :as scheduling]
   [org.akvo.dash.component.tenants :refer [connection]]
   [org.akvo.dash.endpoint.util :refer [rr squuid str->uuid]]
   [org.akvo.dash.transformation :as t]
   [org.akvo.dash.import :as import]
   [pandect.algo.sha1 :refer [sha1]]
   [clojure.java.jdbc :as jdbc]
   [ring.util.io :refer [string-input-stream]]))


(hugsql/def-db-fns "org/akvo/dash/endpoint/dataset.sql")


(defn handle-datasets-POST!
  ""
  [{:keys [:body :tenant :jwt-claims] :as request} db]
  (let [datasource {:id   (squuid)
                    :spec (get body "source")}
        dataset    {:id         (squuid)
                    :name       (get body "name")
                    :datasource (:id datasource)
                    :author     (json/encode jwt-claims)}
        resp       (jdbc/with-db-transaction [tx db]
                     {:datasource (insert-datasource tx datasource)
                      :dataset    (insert-dataset tx dataset)})]
    (scheduling/schedule #(import/job db
                                     (-> resp :datasource first)
                                     (-> resp :dataset first)))
    (rr (select-keys (-> resp :dataset first)
                     [:id :name :status :created :modified]))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;
;;;  Endpoint spec

(defn endpoint
  [{lord :lord :as config}]

  (context "/datasets" []

    (GET "/" []
      (fn [{tenant :tenant :as request}]
        (pprint request)
        (rr (all-datasets (connection lord tenant)))))

    (POST "/" []
      (fn [{tenant :tenant :as request}]
        (try
          (handle-datasets-POST! request
                                 (connection lord tenant))
          (catch Exception e
            (pprint e)
            (pprint (.getNextExcpetion e))
            (rr {:error  "Could not complete upload."
                 :status "FAILURE"})))))


    (context "/:id" [id]

      (GET "/" []
        (fn [{tenant :tenant :as request}]
          (let [{:keys [:id :d :name :status :created :modified] :as ds}
                (dataset-by-id (connection lord tenant)
                               {:id id})
                resp
                {"id"              id
                 "name"            name
                 "status"          status
                 "created"         created
                 "modified"        modified
                 "transformations" []}]
            (rr (if d
                  (assoc resp :columns d)
                  resp)))))

      (PUT "/" []
        (fn [{tenant :tenant :as request}]
          (try
            (update-dataset-name (connection lord tenant)
                                 {:id   id
                                  :name (get-in request [:body "name"])})
            (rr "\"OK\"")
            (catch Exception e
              (pprint e)
              (pprint (.getNextException e))
              (rr "\"FAILURE\""))))))))
