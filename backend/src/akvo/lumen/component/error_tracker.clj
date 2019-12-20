(ns akvo.lumen.component.error-tracker
  (:require [akvo.lumen.protocols :as p]
            [integrant.core :as ig]
            [raven-clj.core :as raven]
            [integrant.core :as ig]
            [raven-clj.ring]
            [clojure.spec.alpha :as s]
            [raven-clj.interfaces :as raven-interface]))

(defrecord SentryErrorTracker [dsn])

(defn sentry-error-tracker [dsn]
  (SentryErrorTracker. dsn))

(defrecord LocalErrorTracker [store])

(defn local-error-tracker []
  (->LocalErrorTracker (atom [])))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/local  [_ opts]
  (local-error-tracker))

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/prod [_]
  empty?)

(defmethod ig/init-key :akvo.lumen.component.error-tracker/prod  [_ {:keys [dsn] :as opts}]
  (sentry-error-tracker dsn))

(s/def ::dsn string?)

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/prod [_]
  (s/keys :req-un [::dsn]))


(defn event-map [error]
  (let [text (str (ex-data error))]
    {:extra {:ex-data (subs text 0 (min (count text) 4096))}
     :message (.getMessage error)}))

(extend-protocol p/IErrorTracker

  SentryErrorTracker
  (track [{:keys [dsn]} error]
    (let [app-namespaces ["org.akvo" "akvo"]
          event-map (event-map error)
          event-info (raven-interface/stacktrace event-map error app-namespaces)]
      (future (raven/capture dsn event-info))))

  LocalErrorTracker
  (track [this error]
    (swap! (:store this) conj (event-map error))
    (println "LocalErrorTracker:" (.getMessage error))))

(defmethod ig/init-key :akvo.lumen.component.error-tracker/wrap-sentry  [_ {:keys [dsn opts]}]
  #(raven-clj.ring/wrap-sentry % dsn opts))


(s/def ::error-tracker (partial satisfies? p/IErrorTracker))

(s/def ::dsn string?)
(s/def ::namespaces (s/coll-of string?))
(s/def ::opts (s/keys :req-un [::namespaces]))

(defmethod ig/pre-init-spec :akvo.lumen.component.error-tracker/wrap-sentry [_]
  (s/keys :req-un [::dsn ::opts] ))
