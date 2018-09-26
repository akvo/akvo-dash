(ns akvo.lumen.component.http
  "Immutant web Duct component."
  (:require [com.stuartsierra.component :as component]
            [clojure.tools.logging :as log]
            [integrant.core :as ig]
            [immutant.web :as web]))

(defrecord Http [app]
  component/Lifecycle

  (start [this]
    (if (:server this)
      this
      (assoc this :server (web/run
                            (-> this :app :handler)
                            (assoc {:host "0.0.0.0"}
                                   :port (:port this))))))

  (stop [this]
    (when-some [server (:server this)]
      (web/stop server))
    (dissoc this :server)))

(defn http [options]
  (map->Http options))

(defmethod ig/init-key :akvo.lumen.component.http  [_ {:keys [config app] :as opts}]
  (log/debug "init-key"  opts)
  (component/start (http (assoc (-> config :http) :app app))))

(defmethod ig/halt-key! :akvo.lumen.component.http  [_ opts]
  (log/debug "halt-key"  opts)
  (component/stop opts)
)
