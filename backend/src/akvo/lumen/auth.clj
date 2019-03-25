(ns akvo.lumen.auth
  (:require [akvo.commons.jwt :as jwt]
            [akvo.lumen.component.flow :as c.flow]
            [akvo.lumen.component.keycloak :as keycloak]
            [akvo.lumen.protocols :as p]
            [cheshire.core :as json]
            [clj-http.client :as client]
            [clojure.set :as set]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.tools.logging :as log]
            [hugsql.core :as hugsql]
            [integrant.core :as ig]
            [ring.util.response :as response]))

(defn claimed-roles [jwt-claims]
  (set (get-in jwt-claims ["realm_access" "roles"])))

(defn tenant-user?
  [{:keys [tenant jwt-claims]}]
  (contains? (claimed-roles jwt-claims)
             (format "akvo:lumen:%s" tenant)))

(defn tenant-admin?
  [{:keys [tenant jwt-claims]}]
  (contains? (claimed-roles jwt-claims)
             (format "akvo:lumen:%s:admin" tenant)))

(defn admin-path? [{:keys [path-info]}]
  (string/starts-with? path-info "/api/admin/"))

(defn api-path? [{:keys [path-info]}]
  (string/starts-with? path-info "/api/"))

(def not-authenticated
  (-> (response/response "Not authenticated")
      (response/status 401)))

(def not-authorized
  (-> (response/response "Not authorized")
      (response/status 403)))

(defn wrap-auth
  "Wrap authentication for API. Allow GET to root / and share urls at /s/<id>.
  If request don't contain claims return 401. If current dns label (tenant) is
  not in claimed roles return 403.
  Otherwiese grant access. This implies that access is on tenant level."
  [handler]
  (fn [{:keys [jwt-claims] :as request}]
    (cond
      (nil? jwt-claims) not-authenticated
      (admin-path? request) (if (tenant-admin? request)
                              (handler request)
                              not-authorized)
      (api-path? request) (if (tenant-user? request)
                            (handler request)
                            not-authorized)
      :else not-authorized)))

(defn wrap-jwt
  "Go get cert from Keycloak and feed it to wrap-jwt-claims. Keycloak url can
  be configured via the KEYCLOAK_URL env var."
  [{:keys [url realm]}]
  (fn [handler]
   (try
     (let [issuer (str url "/realms/" realm)
           certs  (-> (str issuer "/protocol/openid-connect/certs")
                      client/get
                      :body)]
       (jwt/wrap-jwt-claims handler (jwt/rsa-key certs 0) issuer))
     (catch Exception e
       (println "Could not get cert from Keycloak")
       (throw e)))))

(defmethod ig/init-key :akvo.lumen.auth/wrap-auth  [_ {:keys [tenant-manager flow-api] :as opts}]
 wrap-auth)

(defmethod ig/pre-init-spec :akvo.lumen.auth/wrap-auth [_]
  empty?)

(defmethod ig/init-key :akvo.lumen.auth/wrap-jwt  [_ {:keys [keycloak]}]
  (wrap-jwt keycloak))

(s/def ::keycloak ::keycloak/data)
(defmethod ig/pre-init-spec :akvo.lumen.auth/wrap-jwt [_]
  (s/keys :req-un [::keycloak]))

(hugsql/def-db-fns "akvo/lumen/lib/dataset.sql")

(defn wrap-auth-datasets
  "Add to the request the auth-datasets allowed to the current authenticated user,
  using flow-api check_permissions"
  [tenant-manager flow-api]
  (fn [handler]
    (let [auth-calls #{(with-meta ["/api/library"] {:methods #{:get}})
                       (with-meta ["/api/datasets"] {:methods #{:get}})
                       (with-meta ["/api/datasets/:id"] {:methods #{:get :put :delete}})
                       (with-meta ["/api/datasets/:id/meta"] {:methods #{:get}})
                       (with-meta ["/api/datasets/:id/update"] {:methods #{:post}})
                       (with-meta ["/api/aggregation/:dataset-id/:visualisation-type"] {:methods #{:get}})
                       (with-meta ["/api/dashboards"] {:methods #{:get :post}})
                       (with-meta ["/api/visualisations/maps"] {:methods #{:post}})
                       (with-meta ["/api/visualisations"] {:methods #{:get :post}})
                       (with-meta ["/api/visualisations/:id"] {:methods #{:get :put :delete}})}]
      (fn [{:keys [jwt-claims tenant] :as request}]
        (let [dss (all-datasets (p/connection tenant-manager tenant))
              _ (log/debug :all-datasets (map :id dss))
              request (if (-> (get auth-calls [(:template (:reitit.core/match request))])
                              meta
                              :methods
                              (contains? (:request-method request)))
                        (let [permissions (->> (map :source dss)
                                               (filter #(and (= (get % "instance") "uat1") (= "AKVO_FLOW" (get % "kind"))))
                                               (map c.flow/>api-model)
                                               (c.flow/check-permissions flow-api (jwt/jwt-token request))
                                               :body
                                               set)
                              
                              auth-datasets (->> dss
                                                 (filter
                                                  (fn [ds]
                                                    (let [source (:source ds)]
                                                      (if (= "AKVO_FLOW" (get source "kind"))
                                                        (contains? permissions (c.flow/>api-model source))
                                                        true))))
                                                 (mapv :id))]
                          (assoc request :auth-datasets auth-datasets))
                        request)]
          (handler request))))))

(defmethod ig/init-key :akvo.lumen.auth/wrap-auth-datasets  [_ {:keys [tenant-manager flow-api] :as opts}]
 (wrap-auth-datasets tenant-manager flow-api))

(defmethod ig/pre-init-spec :akvo.lumen.auth/wrap-auth-datasets [_]
  ;; todo
  any?)

(defn authenticate-dataset
  ""
  [id-key]
  (fn [handler]
    (fn [{path-params :path-params
          auth-datasets :auth-datasets
          :as request}]
      (let [dataset-id (id-key path-params)]
        (log/error :authenticate-dataset-middleware (contains? (set auth-datasets) dataset-id) dataset-id (set auth-datasets))
        (if (contains? (set auth-datasets) dataset-id)
          (handler request)
          not-authorized)))))
