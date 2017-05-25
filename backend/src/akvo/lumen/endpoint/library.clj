(ns akvo.lumen.endpoint.library
  (:require [akvo.lumen.component.tenant-manager :refer [connection]]
            [akvo.lumen.lib
             [dashboard :as dashboard]
             [dataset :as dataset]
             [visualisation :as visualisation]
             [collection :as collection]]
            [compojure.core :refer :all]
            [ring.util.response :refer [response]]))


(defn endpoint [{:keys [tenant-manager]}]
  (context "/api/library" {:keys [tenant] :as request}
    (let-routes [tenant-conn (connection tenant-manager tenant)]

      (GET "/" _
        (response
         {:dashboards (:body (dashboard/all tenant-conn))
          :datasets (:body (dataset/all tenant-conn))
          :visualisations (:body (visualisation/all tenant-conn))
          :collections (:body (collection/all tenant-conn))})))))
