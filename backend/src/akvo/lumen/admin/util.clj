(ns akvo.lumen.admin.util
  (:require [akvo.lumen.component.keycloak :as keycloak]
            [clojure.java.jdbc :as jdbc]
            [environ.core :refer [env]]))

(defn exec!
  "Execute SQL expression"
  [db-uri format-str & args]
  (jdbc/execute! db-uri
                 [(apply format format-str args)]
                 {:transaction? false}))

(defn db-uri
  "Build a db uri string using standard PG environment variables as fallback"
  ([] (db-uri {}))
  ([{:keys [host database user password]
     :or {host (env :pg-host)
          database (env :pg-database)
          user (env :pg-user)
          password (env :pg-password)}}]
   (format "jdbc:postgresql://%s/%s?user=%s%s%s"
           host database user
           (if (or (= host "localhost") (= host "postgres") )
             ""
             (format "&password=%s" password))
           (if (or (= host "localhost") (= host "postgres"))
             ""
             "&ssl=true"))))

(defn create-keycloak []
  (let [url (format "%s/auth" (:kc-url env))
        issuer (format "%s/realms/akvo" url)]
    {:api-root (format "%s/admin/realms/akvo" url)
     :issuer issuer
     :openid-config (keycloak/fetch-openid-configuration issuer)
     :credentials {"client_id" (:kc-id env "akvo-lumen-confidential")
                   "client_secret" (:kc-secret env)}}))
