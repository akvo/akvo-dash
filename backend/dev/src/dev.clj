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
            #_[duct.util.system :refer [load-system]]
            [integrant.repl.state :as state]
            [integrant.core :as ig]
            [akvo.lumen.middleware]
            [integrant.repl :as ir]
            [reloaded.repl :refer [system init start stop go reset]])
  (:import [org.postgresql.util PSQLException PGobject]))

(duct/load-hierarchy)
(defn read-config []
  (duct/read-config (io/resource "dev.edn")))

(derive :akvo.lumen.component.emailer/dev-emailer   :akvo.lumen.component.emailer/emailer)
#_(underive :akvo.lumen.component.emailer/dev-emailer   :akvo.lumen.component.emailer/emailer )
#_(derive :akvo.lumen.component.emailer/mailjet-emailer   :akvo.lumen.component.emailer/emailer)
#_(underive :akvo.lumen.component.emailer/mailjet-emailer   :akvo.lumen.component.emailer/emailer)

(def config ((ir/set-prep! (comp duct/prep read-config))))

#_(duct/load-hierarchy)
#_(ig/init config [:akvo.lumen.component.emailer/emailer])
#_config
#_(println "JOR:>"(:akvo.lumen.component.emailer/dev-emailer state/system))
#_(println "JOR:>"(:akvo.lumen.config state/system))
#_(ir/halt)

(ir/go)
(:akvo.lumen.component.tenant-manager state/system)




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

#_(defn- seed-tenant
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

#_(defn seed
  "At the moment only support seed of tenants table."
  []
  (let [db-uri (-> (lumen-migrate/construct-system)
                   :config :db :uri)]
    (doseq [tenant (->> "seed.edn" io/resource slurp edn/read-string
                        :tenant-manager :tenants)]
      (seed-tenant {:connection-uri db-uri} tenant))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Migrate
;;;

#_(defn migrate []
  (lumen-migrate/migrate))

#_(defn migrate-and-seed []
  (migrate)
  (seed)
  (migrate))

#_(defn rollback
  ([] (lumen-migrate/rollback {}))
  ([args] (lumen-migrate/rollback args)))
