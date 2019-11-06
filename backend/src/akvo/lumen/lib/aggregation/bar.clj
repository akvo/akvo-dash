(ns akvo.lumen.lib.aggregation.bar
  (:require [akvo.lumen.lib :as lib]
            [akvo.lumen.lib.aggregation.commons :refer (run-query)]
            [akvo.lumen.lib.dataset.utils :refer (find-column)]
            [akvo.lumen.postgres.filter :refer (sql-str)]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]
            [clojure.pprint :refer (pprint)]
            [clojure.tools.logging :as log]))

(defn- sort* [v column]
  (case v
    nil   "x ASC"
    "asc" (format "%s ASC NULLS FIRST" column)
    "dsc" (format "%s DESC NULLS LAST" column)))

(defn- subbucket-sql [table-name bucket-column subbucket-column aggregation filter-sql sort-sql truncate-size]
  (format "
          WITH
            sort_table
          AS
            (SELECT %1$s AS x, %2$s AS sort_value
             FROM %3$s 
             WHERE %4$s 
             GROUP BY %1$s 
             ORDER BY %5$s 
             LIMIT %6$s), 
            data_table
          AS
            (SELECT %1$s as x, %2$s as y, %7$s as s
             FROM %3$s
             WHERE %4$s
             GROUP BY %1$s, %7$s) 
          SELECT
            data_table.x AS x,
            data_table.y,
            data_table.s,
            sort_table.sort_value
          FROM
            data_table
          JOIN
            sort_table
          ON
            sort_table.x = data_table.x
          ORDER BY %5$s"
          (:columnName bucket-column)
          aggregation
          table-name
          filter-sql
          sort-sql
          truncate-size
          (:columnName subbucket-column)))

(defn- bucket-sql [table-name bucket-column aggregation filter-sql sort-sql truncate-size]
  (format "SELECT * FROM (SELECT %1$s as x, %2$s as y FROM %3$s WHERE %4$s GROUP BY %1$s)z ORDER BY %5$s LIMIT %6$s"
          (:columnName bucket-column)
          aggregation
          table-name
          filter-sql
          sort-sql
          truncate-size))

(defn- series-bucket-sql [table-name bucket-column aggregations filter-sql sort-sql truncate-size metricColumnsY]
  (format "SELECT * FROM 
(SELECT %1$s as x, %2$s FROM %3$s WHERE %4$s GROUP BY %1$s)z 
ORDER BY %5$s LIMIT %6$s"
          (:columnName bucket-column)
          (str/join ", " aggregations )
          table-name
          filter-sql
          sort-sql
          truncate-size
          metricColumnsY))

(defn- aggregation* [aggregation-method metric-column bucket-column]
  (let [aggregation-method (if-not metric-column "count" (or aggregation-method "count"))]
    (format
     (case aggregation-method
       nil                         "NULL"
       ("min" "max" "count" "sum") (str aggregation-method  "(%s)")
       "mean"                      "avg(%s)"
       "median"                    "percentile_cont(0.5) WITHIN GROUP (ORDER BY %s)"
       "distinct"                  "COUNT(DISTINCT %s)"
       "q1"                        "percentile_cont(0.25) WITHIN GROUP (ORDER BY %s)"
       "q3"                        "percentile_cont(0.75) WITHIN GROUP (ORDER BY %s)")
     (or (:columnName metric-column)
         (:columnName bucket-column)))))

(defn- subbucket-column-response [sql-response bucket-column metric-columns-y]
  (let [bucket-values    (distinct
                          (mapv
                           (fn [[x-value _ _]] x-value)
                           sql-response))
        subbucket-values (if metric-columns-y
                           (mapv :title metric-columns-y)
                           (distinct
                           (mapv
                            (fn [[_ _ s-value]] s-value)
                            sql-response)))]
    {:series
     (if metric-columns-y
       ;; men women
       ;;([Agriculture  30.5 31.5][Education  24.5 24.5] [Other  24.5 18.5] [WASH  32.0 31.0])
       (mapv
        (fn [metric-column i]
          {:key metric-column
           :label metric-column
           :data  (mapv #(assoc {} :value (nth % i)) sql-response)} 
          )
        (mapv :title metric-columns-y)
        (map inc (range))
        )
       
       (mapv
        (fn [s-value]
          {:key   s-value
           :label s-value
           :data
           (map
            (fn
              [bucket-value]
              {:value (or (nth
                           (first
                            (filter
                             (fn [row] (and (= (nth row 0) bucket-value) (= (nth row 2) s-value)))
                             sql-response))
                           1
                           0) 0)})
            bucket-values)})
        subbucket-values))
     :common
     {:metadata
      {:type (:type bucket-column)}
      :data (mapv
             (fn [bucket] {:label bucket
                           :key   bucket})
             bucket-values)}}))

(defn- bucket-column-response [sql-response bucket-column metric-y-column]
  {:series [{:key   (:title metric-y-column)
             :label (:title metric-y-column)
             :data  (mapv (fn [[x-value y-value]]
                            {:value y-value})
                          sql-response)}]
   :common {:metadata {:type (:type bucket-column)}
            :data     (mapv (fn [[x-value y-value]]
                              {:label x-value
                               :key   x-value})
                            sql-response)}})

(defn query
  [tenant-conn {:keys [columns table-name]} query]
  (if-let [bucket-column (find-column columns (:bucketColumn query))]
    (let [subbucket-column (find-column columns (:subBucketColumn query))
          metric-y-column  (or (find-column columns (:metricColumnY query)) subbucket-column)
          metric-aggregation (:metricAggregation query)
          aggregation      (aggregation* metric-aggregation metric-y-column bucket-column)
          metric-columns-y (when (not (empty? (:metricColumnsY query)))
                             (let [metric-columns-y (map #(find-column columns %) (:metricColumnsY query))]
                               (conj metric-columns-y metric-y-column)))
          sql*             (if metric-columns-y
                             (series-bucket-sql table-name
                                                bucket-column
                                                (map #(aggregation* metric-aggregation %  bucket-column) metric-columns-y)
                                                (sql-str columns (:filters query))
                                                (sort* (:sort query) "z.y")
                                                (or (:truncateSize query) "ALL")
                                                metric-columns-y)
                               (if subbucket-column
                                 (subbucket-sql table-name bucket-column subbucket-column aggregation
                                                (sql-str columns (:filters query))
                                                (sort* (:sort query) "sort_value") (or (:truncateSize query) "ALL"))
                                 (bucket-sql table-name bucket-column aggregation
                                             (sql-str columns (:filters query))
                                             (sort* (:sort query) "z.y") (or (:truncateSize query) "ALL"))))
          _ (log/debug :sql* sql*)
          sql-response     (run-query tenant-conn sql*)
          _ (log/debug :sql-response* sql-response)
          max-elements     200]
      (if (> (count sql-response) max-elements)
        (lib/bad-request {"error"  true
                          "reason" "too-many"
                          "max"    max-elements
                          "count"  (count sql-response)})
        (lib/ok
         (if (or subbucket-column metric-columns-y)
           (subbucket-column-response sql-response bucket-column metric-columns-y)
           (bucket-column-response sql-response bucket-column metric-y-column)))))
    (lib/ok (bucket-column-response nil nil nil))))
