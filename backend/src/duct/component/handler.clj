(ns duct.component.handler
  (:require [com.stuartsierra.component :as component]
            [compojure.core :as compojure]
            [integrant.core :as ig]
            [clojure.tools.logging :as log]
            [duct.util.namespace :as ns]))

(defn- find-endpoint-keys [component]
  (sort (map key (filter (comp :routes val) component))))

(defn- find-routes [component]
  (:endpoints component (find-endpoint-keys component)))

(defn- middleware-fn [f args]
  (let [f    (ns/resolve-var f)
        args (if (or (nil? args) (seq? args)) args (list args))]
    #(apply f % args)))

(defn- middleware-map [{:keys [functions arguments]}]
  (reduce-kv (fn [m k v] (assoc m k (middleware-fn v (arguments k)))) {} functions))

(defn- compose-middleware [{:keys [applied] :as middleware}]
  (->> (reverse applied)
       (map (middleware-map middleware))
       (apply comp identity)))

(defrecord Handler [middleware ]
  component/Lifecycle
  (start [component]
    (if-not (:handler component)
      (let [routes  (find-routes component)
            wrap-mw (compose-middleware (:middleware component))
            handler (wrap-mw (apply compojure/routes routes))]
        (assoc component :handler handler))
      component))
  (stop [component]
    (dissoc component :handler)))

(defn handler-component [options]
  (map->Handler options))


(defmethod ig/init-key :akvo.lumen.component.handler/handler  [_ {:keys [endpoints config] :as opts}]
  (log/debug "init-key" :akvo.lumen.component.handler :opts opts)
  (component/start (handler-component {:endpoints endpoints :middleware (-> config :app :middleware)})))

(defmethod ig/halt-key! :akvo.lumen.component.handler/handler  [_ opts]
  (log/debug "halt-key" :akvo.lumen.component.handler opts)
  (component/stop opts))
