(ns dev
  (:refer-clojure :exclude [test])
  (:require [akvo.lumen.endpoint]
            [akvo.lumen.lib.aes :as aes]
            [akvo.lumen.migrate :as lumen-migrate]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer :all]
            [clojure.tools.namespace.repl :refer [refresh]]
            [com.stuartsierra.component :as component]
            [duct.generate :as gen]
            [duct.core :as duct]
            [integrant.repl.state :as state :refer (system)]
            [integrant.core :as ig]
            [akvo.lumen.middleware]
            [integrant.repl :as ir]
            [reloaded.repl :refer [init start stop #_go reset]])
  (:import [org.postgresql.util PSQLException PGobject]))




#_(duct/load-hierarchy)
(defn read-config []
  (duct/read-config (io/resource "dev.edn")))


(derive :akvo.lumen.component.emailer/dev-emailer :akvo.lumen.component.emailer/emailer)
(derive :akvo.lumen.component.caddisfly/local :akvo.lumen.component.caddisfly/caddisfly)
(derive :akvo.lumen.component.error-tracker/local :akvo.lumen.component.error-tracker/error-tracker)

(defn clean [c]
  (dissoc c :akvo.lumen.component.emailer/mailjet-emailer
          :akvo.lumen.component.caddisfly/prod
          :akvo.lumen.component.error-tracker/prod))

(def config ((ir/set-prep!  (comp clean duct/prep read-config))))
(ig/load-namespaces config)
#_(keys config)

(defn go []
  (ir/go))
#_(go)
(defn halt! []
  (ir/halt))

#_(defn new-system []

  #_(load-system
     (keep io/resource
                     ["akvo/lumen/system.edn" "dev.edn" "local.edn"])))



#_(when (io/resource "local.clj")
  (load "local"))

#_(gen/set-ns-prefix 'akvo.lumen)

#_(reloaded.repl/set-init! new-system)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Seed
;;;

(defn- seed-tenant
  "Helper function that will seed tenant to the tenants table."
  [db tenant]
  (try
    (let [{:keys [id]} (first (jdbc/insert! db "tenants" (update (dissoc tenant :plan)
                                                                 :db_uri #(aes/encrypt "secret" %))))]
      (jdbc/insert! db "plan" {:tenant id
                               :tier (doto (org.postgresql.util.PGobject.)
                                       (.setType "tier")
                                       (.setValue (:plan tenant)))}))
    (catch PSQLException e
      (println "Seed data already loaded."))))

(defn seed
  "At the moment only support seed of tenants table."
  []
  (let [db-uri (-> (lumen-migrate/construct-system)
                   :akvo.lumen.config :db :uri)]
    (doseq [tenant (->> "seed.edn" io/resource slurp edn/read-string
                        :tenant-manager :tenants)]
      (seed-tenant {:connection-uri db-uri} tenant))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Migrate
;;;

(defn migrate []
  (lumen-migrate/migrate "dev.edn"))

(defn migrate-and-seed []
  (migrate)
  (seed)
  (migrate))

(defn rollback
  ([] (lumen-migrate/rollback "dev.edn" {}))
  ([args] (lumen-migrate/rollback "dev.edn" args)))

