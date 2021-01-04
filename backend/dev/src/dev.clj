(ns dev
  (:refer-clojure :exclude [test])
  (:require [akvo.lumen.endpoint.commons]
            [akvo.lumen.db.dataset :as db.dataset]
            [akvo.lumen.lib.import.flow :as flow]
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
  (:import [org.postgresql.util PSQLException PGobject]
           [java.time Instant]))

(def dev-flow-datasets-dir "flow-datasets")

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
  (alter-var-root #'flow/adapter (fn [f]
                                   (fn [{:keys [version rows-cols instance survey-id form-id]} data]
                                     (let [file-name (->> (format "%s-%s-%s-%s-%s" instance survey-id form-id (name rows-cols) version)
                                                          (format "./dev/resources/%s/%s.edn" dev-flow-datasets-dir))]
                                       (doseq [d data]
                                         (spit file-name d :append true)))
                                     (f nil data))))
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
   (when-not system (go))
   (new-flow-dataset dataset-name [{:groupId "group1"
                                    :groupName "repeatable group"
                                    :repeatable true
                                    :column-types ["option" "text"]
                                    :max-rqg-answers 10}
                                   {:groupId "group2"
                                    :groupName "not repeatable group"
                                    :repeatable false
                                    :column-types ["number" "date" "geopoint"]}] 2))
  ([dataset-name groups submissions]
   (when-not system (go))
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

(defn read-edn-filename
  "file should live in resources"
  [filename]
  (let [x (->> (format "%s/%s" dev-flow-datasets-dir filename)
               (clojure.java.io/resource)
               (clojure.java.io/file)
               (slurp))]
   ;;binding  [*default-data-reader-fn* tagged-literal]
   (edn/read-string {:readers {'object (fn [o]
                                         (condp = (first o)
                                           'java.time.Instant (Instant/parse (last o))))}}
                    (format "[%s]" x))))

(defn read-edn-flow-dataset [instance survey-id form-id]
  (let [base-name (format "%s-%s-%s" instance survey-id form-id)]
   {:columns-v3 (read-edn-filename (format "%s-%s-%s.edn" base-name "cols" 3))
    :columns-v4 (read-edn-filename (format "%s-%s-%s.edn" base-name "cols" 4))
    :records-v3 (read-edn-filename (format "%s-%s-%s.edn" base-name "rows" 3))
    :records-v4 (read-edn-filename (format "%s-%s-%s.edn" base-name "rows" 4))}))

(comment
  (read-edn-filename "uat1-638889127-638879132-cols-3.edn")
  (def dataset-id (tu/import-file (db-conn)
                                  (:akvo.lumen.utils.local-error-tracker/local system)
                                  {:dataset-name "dataset-name"
                                   :kind "clj-flow"
                                   :data (read-edn-flow-dataset "uat1" "638889127" "638879132")}))

  (def dataset-id-updated (tu/update-file (db-conn)
                                          (:akvo.lumen.component.caddisfly/local system)
                                          (:akvo.lumen.utils.local-error-tracker/local system)
                                          dataset-id
                                          (:id (db.dataset/data-source-by-dataset-id (db-conn) {:dataset-id dataset-id}))
                                          {:dataset-name "dataset-name"
                                           :kind "clj-flow"
                                           :data (read-edn-flow-dataset "uat1" "638889127" "638879132")})))
