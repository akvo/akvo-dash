(ns dev
  (:refer-clojure :exclude [test])
  (:require [akvo.lumen.endpoint.commons]
            [akvo.lumen.lib.aes :as aes]
            [akvo.lumen.migrate :as lumen-migrate]
            [akvo.lumen.db.env :as env]
            [akvo.lumen.protocols :as p]
            [akvo.lumen.specs]
            [akvo.lumen.specs.import :as i-c]
            [akvo.lumen.test-utils :as tu]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as jdbc]
            [clojure.pprint :refer [pprint]]
            [clojure.repl :refer :all]
            [akvo.lumen.component.tenant-manager]
            [clojure.spec.alpha :as s]
            [clojure.spec.test.alpha :as stest]
            [clojure.tools.logging :as log]
            [clojure.tools.namespace.repl :as repl]
            [dev.commons :as commons]
            [duct.core :as duct]
            [duct.generate :as gen]
            [integrant.core :as ig]
            [integrant.repl :as ir]
            [integrant.repl.state :as state :refer (system)])
  (:import [org.postgresql.util PSQLException PGobject]))

(defn check-specs! []
  (log/warn "instrumenting specs!")
  (stest/instrument))

(defn uncheck-specs! []
  (log/warn "unstrumenting specs!")
  (stest/unstrument))

(defn refresh []
  (uncheck-specs!)
  (repl/refresh)
  (check-specs!))

(defn go []
  (commons/config)
  (ir/go))

(defn halt! []
  (ir/halt))

(def stop halt!)

(def reset go)

(when (io/resource "local.clj")
  (load "local"))

(gen/set-ns-prefix 'akvo.lumen)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Seed
;;;


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Migrate
;;;

(defn migrate []
  (lumen-migrate/migrate (commons/config)))

(defn migrate-and-seed []
  (migrate)
  (tu/seed (commons/config))
  (migrate))

(defn rollback
  ([] (lumen-migrate/rollback (commons/config) {}))
  ([args] (lumen-migrate/rollback (commons/config) args)))

(defn reset-db []
  (rollback)
  (migrate))

(defn db-conn
  ([label] (p/connection (:akvo.lumen.component.tenant-manager/tenant-manager system) label))
  ([] (db-conn "t1")))

(defn new-flow-dataset
  ([dataset-name]
   (new-flow-dataset dataset-name [{:groupId "group1"
                                    :groupName "repeatable group"
                                    :repeatable true
                                    :column-types ["option" "text"]
                                    :max-responses 10}
                                   {:groupId "group2"
                                    :groupName "not repeatable group"
                                    :repeatable false
                                    :column-types ["number" "date"]}] 2))
  ([dataset-name groups submissions]
   (tu/import-file (db-conn)
                   (:akvo.lumen.utils.local-error-tracker/local system)
                   {:dataset-name dataset-name
                    :kind "clj-flow"
                    :data (i-c/flow-sample-imported-dataset groups submissions)})))

(defn flags []
  (env/all-values (db-conn)))

(defn activate-flag [flag]
  (env/activate-flag (db-conn) flag))

(defn deactivate-flag [flag]
  (env/deactivate-flag (db-conn) flag))
