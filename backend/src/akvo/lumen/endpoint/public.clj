(ns akvo.lumen.endpoint.public
  (:require [akvo.lumen.component.tenant-manager :refer [connection]]
            [akvo.lumen.lib.public :as public]
            [cheshire.core :as json]
            [compojure.core :refer :all]))


(defn endpoint [{:keys [tenant-manager config]}]
  (context "/share" {:keys [params tenant headers] :as request}
    (let-routes [tenant-conn (connection tenant-manager tenant)]

      (GET "/:id" [id]
        (let [password (get headers "x-password")]
          (public/share tenant-conn config id password))))))
