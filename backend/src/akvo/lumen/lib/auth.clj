(ns akvo.lumen.lib.auth
  (:require
   [akvo.commons.jwt :as jwt]
   [akvo.lumen.component.flow :as c.flow]
   [akvo.lumen.component.tenant-manager :as tenant-manager]
   [akvo.lumen.specs :as lumen.s]
   [akvo.lumen.specs.dataset :as dataset.s]
   [akvo.lumen.specs.visualisation :as visualisation.s]
   [akvo.lumen.protocols :as p]
   [clojure.spec.alpha :as s]
   [clojure.set :as set]
   [clojure.string :as str]
   [clojure.tools.logging :as log]
   [clojure.set :as set]
   [hugsql.core :as hugsql]
   [integrant.core :as ig]
   [reitit.core :as rc]))

(hugsql/def-db-fns "akvo/lumen/lib/visualisation.sql")
(hugsql/def-db-fns "akvo/lumen/lib/dataset.sql")

(defrecord AuthServiceImpl [auth-datasets-set]
  p/AuthService
  (auth? [this {:keys [dataset-ids]}]
    {:auth-datasets (set/intersection auth-datasets-set (set dataset-ids))})
  (auth? [this type* uuid]
    (condp = type*
      :dataset (when (contains? auth-datasets-set uuid) uuid))))

(defn new-auth-service [{:keys [auth-datasets] :as auth-uuid-tree}]
  (AuthServiceImpl. (set auth-datasets)))

(defn match-by-jwt-family-name?
  "Feature flag condition based on `First Name` jwt-claims
  To match just add `$auth` to your `First Name` in your related auth system user profile"
  [request]
  (str/includes? (get (:jwt-claims request) "given_name" "") "$auth$"))

(defn match-by-template-and-method?
  "`data` is a map following this structure {\"/api/library\" {:methods #{:get}}}
  `request` is a `reitit` request that contains reitit data such as :template"
  [data request]
  (-> (get data (:template (:reitit.core/match request)))
      :methods
      (contains? (:request-method request))))

(def auth-calls {"/api/library" {:methods #{:get}}
                 "/api/datasets/:id" {:methods #{:get :put :delete}}
                 "/api/datasets/:id/meta" {:methods #{:get}}
                 "/api/datasets/:id/update" {:methods #{:post}}
                 "/api/datasets" {:methods #{:get}}
                 "/api/visualisations" {:methods #{:get :post}}
                 "/api/visualisations/:id" {:methods #{:get :put :delete}}
                 "/api/visualisations/maps" {:methods #{:post}}})

(defn- auth-datasets [all-datasets permissions]
  (->> all-datasets
       (filter
        (fn [ds]
          (let [source (:source ds)]
            (if (= "AKVO_FLOW" (get source "kind"))
              (contains? permissions (c.flow/>api-model source))
              true))))
       (mapv :id)))

(defn- auth-visualisations [tenant-conn auth-datasets-set]
  (let [maps* (->> (all-maps-visualisations-with-dataset-id tenant-conn {} {} {:identifiers identity})
                   (reduce (fn [c {:keys [id datasetId]}] (update c id #(set (conj % datasetId)))) {})
                   (filter #(set/superset? auth-datasets-set (val %)))
                   (mapv first))
        others* (mapv :id (all-no-maps-visualisations tenant-conn {} {} {:identifiers identity}))]
    (log/debug :vis-maps* maps*)
    (log/debug :vis-others* others*)
    (apply conj others* maps*)))

(defn wrap-auth-datasets
  "Add to the request an auth-service protocol impl using flow-api check_permissions"
  [tenant-manager flow-api]
  (fn [handler]
    (fn [{:keys [jwt-claims tenant] :as request}]
      (let [tenant-conn    (p/connection tenant-manager tenant)
            dss            (all-datasets tenant-conn)
            auth-uuid-tree (if (and (match-by-jwt-family-name? request)
                                    (match-by-template-and-method? auth-calls request))
                             (let [permissions   (->> (map :source dss)
                                                      (filter #(= "AKVO_FLOW" (get % "kind")))
                                                      (map c.flow/>api-model)
                                                      (c.flow/check-permissions flow-api (jwt/jwt-token request))
                                                      :body
                                                      set)
                                   auth-datasets (auth-datasets dss permissions)]
                               {:auth-datasets       auth-datasets
                                :auth-visualisations (auth-visualisations tenant-conn (set auth-datasets))})
                             {:auth-datasets       (mapv :id dss)
                              :auth-visualisations (mapv :id (all-visualisations tenant-conn))})]
        (handler (assoc request
                        :auth-service (new-auth-service auth-uuid-tree)))))))

(defmethod ig/init-key :akvo.lumen.lib.auth/wrap-auth-datasets  [_ {:keys [tenant-manager flow-api] :as opts}]
  (wrap-auth-datasets tenant-manager flow-api))

(s/def ::flow-api ::c.flow/config)

(defmethod ig/pre-init-spec :akvo.lumen.lib.auth/wrap-auth-datasets [_]
  (s/keys :req-un [::tenant-manager/tenant-manager
                   ::flow-api]))

(defn ids [spec data]
  (let [ids (atom {:dataset-ids #{}
                   :visualisation-ids #{}})
        original-vis-id? visualisation.s/*id?*
        original-ds-id? dataset.s/*id?*
        ds-fun (fn [id]
                 (swap! ids update :dataset-ids conj id)
                 (try
                   (original-vis-id? id)
                   (catch Exception e false)))
        vis-fun (fn [id]
                  (swap! ids update :visualisation-ids conj id)
                  (try
                    (original-ds-id? id)
                    (catch Exception e false)))]
    (binding [visualisation.s/*id?* vis-fun
              dataset.s/*id?* ds-fun]
      (let [explain (s/explain-str spec data)]
        (swap! ids assoc :spec-valid? (s/valid? spec data))
        (deref ids)))))
