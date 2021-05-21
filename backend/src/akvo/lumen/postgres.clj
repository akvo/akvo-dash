(ns akvo.lumen.postgres
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [akvo.lumen.protocols :as p])
  (:import [org.postgis Polygon MultiPolygon PGgeometry LineString MultiPoint]
           [org.postgresql.util PGobject]
           [clojure.lang PersistentVector]))

(defn column-id [column]
  (or (:id column) (:columnName column)))

(defn escape-string [s]
  (when-not (nil? s)
    (when-not (string? s)
      (throw (ex-info "Not a string" {:s s})))
    (str/replace s "'" "''")))

(defn adapt-string-value [v]
  (str "$anylumenthing$" v "$anylumenthing$::TEXT"))

(defn val->geometry-pgobj
  [v]
  (doto (PGobject.)
    (.setType "geometry")
    (.setValue (.toString v))))

(extend-protocol jdbc/ISQLValue
  org.postgis.Polygon
  (sql-value [v] (val->geometry-pgobj v))
  org.postgis.MultiPolygon
  (sql-value [v] (val->geometry-pgobj v))
  org.postgis.Point
  (sql-value [v] (val->geometry-pgobj v))
  org.postgis.LineString
  (sql-value [v] (val->geometry-pgobj v))
  org.postgis.MultiPoint
  (sql-value [v] (val->geometry-pgobj v)))

(defn- index-name [table-name column-name]
  (format "%s_%s_idx" table-name column-name))

(defn geo-index [table-name column-name]
  (format "CREATE INDEX %s ON %s USING GIST(%s)"
          (index-name table-name column-name)
          table-name
          column-name))

(defn- create-indexes [conn table-name columns]
  (doseq [column columns]
    (condp = (:type column)
      "geoshape"
      (jdbc/execute! conn (geo-index table-name (name (column-id column))))
      "geopoint"
      (jdbc/execute! conn (geo-index table-name (name (column-id column))))

      ;; else
      nil)))

(defn- add-key-constraints [conn table-name columns]
  (doseq [column columns
          :when (:key column)]
    (jdbc/execute! conn
                   (format "ALTER TABLE \"%s\" ADD UNIQUE (%s)"
                           table-name
                           (name (column-id column))))
    (jdbc/execute! conn
                   (format "ALTER TABLE \"%s\" ALTER COLUMN %s SET NOT NULL"
                           table-name
                           (name (column-id column))))))
(defn colum-type-fn* [type]
  (condp = type
    "date" "timestamptz"
    "number" "double precision"
    ;; Note not `POLYGON` so we can support `MULTIPOLYGON` as well
    "geoshape" "geometry(GEOMETRY, 4326)"
    "geomultipoint" "geometry(MULTIPOINT, 4326)"
    "geoline" "geometry(LINE, 4326)"
    "geopoint" "geometry(POINT, 4326)"
    "multiple" "text"
    "option" "text"
    "text" "text"))

(defn- column-type-fn [column]
  (format "%s %s"
          (name (column-id column))
          (colum-type-fn* (:type column))))

(defn create-dataset-table [conn table-name columns]
  (jdbc/execute! conn [(format "create table %s (rnum serial primary key, %s);"
                               table-name
                               (str/join ", " (map column-type-fn columns)))])
  (create-indexes conn table-name columns)
  (add-key-constraints conn table-name columns))

(defn create-data-group-foreign-keys [conn group-tables]
  (when-let [metadata-table-name (get group-tables "metadata")]
    (let [fks (for [[group-id group-table-name] group-tables
                    :when (not (= "metadata" group-id))]
                (format "ALTER TABLE %s ADD FOREIGN KEY (instance_id) REFERENCES %s (instance_id) ON DELETE CASCADE"
                        group-table-name metadata-table-name))]
      (doseq [fk fks]
        (jdbc/execute! conn fk)))))

(defrecord Geoshape [wkt-string])

(defrecord Geopoint [wkt-string])

(defrecord Geoline [wkt-string])

(defrecord Multipoint [wkt-string])

(extend-protocol p/CoerceToSql
  java.lang.String
  (coerce [value] value)
  java.lang.Number
  (coerce [value] value)
  java.time.Instant
  (coerce [value]
    (java.sql.Timestamp. (.toEpochMilli value)))
  Geoshape
  (coerce [value]
    (let [geom (PGgeometry/geomFromString (:wkt-string value))]
      (.setSrid geom 4326)
      geom))
  Geoline
  (coerce [value]
    (let [geom (PGgeometry/geomFromString (:wkt-string value))]
      (.setSrid geom 4326)
      geom))
  Multipoint
  (coerce [value]
    (let [geom (PGgeometry/geomFromString (:wkt-string value))]
      (.setSrid geom 4326)
      geom))
  Geopoint
  (coerce [value]
    (let [geom (PGgeometry/geomFromString (:wkt-string value))]
      (.setSrid geom 4326)
      geom))
  PGobject
  (coerce [value] value)
  )

(defn coerce-to-sql [record]
  (reduce-kv
   (fn [result k v]
     (assoc result k (when v (p/coerce v))))
   {}
   record))
