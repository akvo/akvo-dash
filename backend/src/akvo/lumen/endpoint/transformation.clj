(ns akvo.lumen.endpoint.transformation
  (:require [akvo.lumen.protocols :as p]
            [akvo.lumen.lib.transformation :as t]
            [akvo.lumen.component.caddisfly :as caddisfly]
            [clojure.spec.alpha :as s]
            [akvo.lumen.component.tenant-manager :as tenant-manager]
            [integrant.core :as ig]))

(defn routes [{:keys [tenant-manager caddisfly] :as opts}]
  ["/transformations/:dataset-id"
   [["/transform/:op1/:op2"
     {:monitoring {:unwrap? [:op1 :op2]}
      :post {:parameters {:path-params {:dataset-id string?}
                           :body map?}
              :handler (fn [{tenant :tenant
                             body :body
                             jwt-claims :jwt-claims
                             {:keys [dataset-id op1 op2]} :path-params}]
                         (t/apply {:tenant-conn (p/connection tenant-manager tenant) :caddisfly caddisfly :claims jwt-claims}
                                  dataset-id
                                  {:type :transformation
                                   :transformation body}))}}]]
   [["/undo"
      {:post {:parameters {:path-params {:dataset-id string?}}
              :handler (fn [{tenant :tenant
                             jwt-claims :jwt-claims
                             {:keys [dataset-id]} :path-params}]
                         (t/apply {:tenant-conn (p/connection tenant-manager tenant) :caddisfly caddisfly :claims jwt-claims}
                                  dataset-id
                                  {:type :undo}))}}]]])

(defmethod ig/pre-init-spec :akvo.lumen.endpoint.transformation/transformation [_]
  (s/keys :req-un [::tenant-manager/tenant-manager
                   ::caddisfly/caddisfly]))

(defmethod ig/init-key :akvo.lumen.endpoint.transformation/transformation  [_ opts]
  (routes opts))
