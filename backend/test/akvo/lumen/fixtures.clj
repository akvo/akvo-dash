(ns akvo.lumen.fixtures
  (:require [akvo.lumen.test-utils :as test-utils]
            [akvo.lumen.test-utils
             :refer
             [import-file test-tenant test-tenant-conn]]
            [ragtime.jdbc :as jdbc]
            [ragtime.repl :as repl]))


(defn- ragtime-spec
  [tenant]
  {:datastore  (jdbc/sql-database {:connection-uri (:db_uri tenant)})
   :migrations (jdbc/load-resources "akvo/lumen/migrations/tenants")})

(defn migrate-tenant
  [tenant]
  (repl/migrate (ragtime-spec tenant)))

(defn rollback-tenant
  [tenant]
  (let [spec (ragtime-spec tenant)]
    (repl/rollback spec (count (:migrations spec)))))

(defn- user-manager-ragtime-spec []
  {:datastore
   (jdbc/sql-database {:connection-uri (:db_uri (test-utils/test-tenant-manager))})
   :migrations
   (jdbc/load-resources "akvo/lumen/migrations/tenant_manager")})

(defn migrate-user-manager []
  (repl/migrate (user-manager-ragtime-spec)))

(def ^:dynamic *tenant-conn*)

(defn tenant-conn-fixture
  "Returns a fixture that binds a connection pool to *tenant-conn*"
  [f]
  (migrate-tenant test-tenant)
  (binding [*tenant-conn* (test-tenant-conn test-tenant)]
    (f)
    (rollback-tenant test-tenant)))
