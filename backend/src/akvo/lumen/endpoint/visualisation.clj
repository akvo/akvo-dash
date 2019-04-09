(ns akvo.lumen.endpoint.visualisation
  (:require [akvo.lumen.protocols :as p]
            [akvo.lumen.lib.visualisation :as visualisation]
            [akvo.lumen.specs.visualisation :as visualisation.s]
            [akvo.lumen.specs.visualisation.maps :as visualisation.maps.s]
            [akvo.lumen.lib :as lib]
            [akvo.lumen.lib.visualisation.maps :as maps]
            [akvo.lumen.lib.auth :as l.auth]
            [clojure.spec.alpha :as s]
            [akvo.lumen.component.tenant-manager :as tenant-manager]
            [clojure.walk :as w]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]))

(defn all-visualisations [auth-service tenant-conn]
  (let [vis-col (visualisation/all tenant-conn)
        ids (l.auth/ids ::visualisation.s/visualisations vis-col)
        auth-vis-col (:auth-visualisations (p/auth? auth-service ids))
        auth-res (filter #(contains? auth-vis-col (:id %)) vis-col)]
    (lib/ok auth-res)))

(defn routes [{:keys [windshaft-url tenant-manager] :as opts}]
  ["/visualisations"
   ["" {:get {:handler (fn [{tenant :tenant
                             auth-service :auth-service}]
                         (all-visualisations auth-service (p/connection tenant-manager tenant)))}
        :post {:parameters {:body map?}
               :handler (fn [{tenant :tenant
                              jwt-claims :jwt-claims
                              body :body}]
                          (let [vis-payload (w/keywordize-keys body)]
                            (visualisation/create (p/connection tenant-manager tenant) vis-payload jwt-claims)))}}]
   ["/maps" ["" {:post {:parameters {:body map?}
                        :handler (fn [{tenant :tenant
                                       body :body}]
                                   (let [{:strs [spec]} body
                                         layers (w/keywordize-keys (get-in spec ["layers"]))]
                                     (maps/create (p/connection tenant-manager tenant) windshaft-url layers)))}}]]
   ;; rasters don't depend on flow data (yet!), so no need to wrap this call 
   ["/rasters" ["" {:post {:parameters {:body map?}
                           :handler (fn [{tenant :tenant
                                          body :body}]
                                      (let [{:strs [rasterId spec]} body]
                                        (maps/create-raster (p/connection tenant-manager tenant) windshaft-url rasterId)))}}]]
   ;; todo: fix path routing inconsistency here 
   ["/:id" ["" {:get {:parameters {:path-params {:id string?}}
                      :handler (fn [{tenant :tenant
                                     {:keys [id]} :path-params}]
                                   (visualisation/fetch (p/connection tenant-manager tenant) id))}
                  :put {:parameters {:body map?
                                     :path-params {:id string?}}
                        :handler (fn [{tenant :tenant
                                       jwt-claims :jwt-claims
                                       {:keys [id]} :path-params
                                       body :body}]
                                   (let [vis-payload (w/keywordize-keys body)]
                                     (visualisation/upsert (p/connection tenant-manager tenant) vis-payload jwt-claims)))}
                  :delete {:parameters {:path-params {:id string?}}
                           :handler (fn [{tenant :tenant
                                          {:keys [id]} :path-params}]
                                      (visualisation/delete (p/connection tenant-manager tenant) id))}}]]])

(defmethod ig/init-key :akvo.lumen.endpoint.visualisation/visualisation  [_ opts]
  (routes opts))

(s/def ::windshaft-url string?)

(defmethod ig/pre-init-spec :akvo.lumen.endpoint.visualisation/visualisation [_]
  (s/keys :req-un [::tenant-manager/tenant-manager
                   ::windshaft-url] ))
