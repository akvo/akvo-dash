(ns akvo.lumen.lib.visualisation.map-metadata
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.string :as str]))

(def palette ["#BF2932"
              "#19A99D"
              "#95734B"
              "#86AA90"
              "#66608F"
              "#FEDA77"
              "#C0652A"
              "#5286B4"
              "#C28A6F"
              "#61B66F"
              "#3D3455"
              "#D8BB7F"
              "#158EAE"
              "#5F6253"
              "#921FA1"
              "#F38341"
              "#487081"
              "#556123"
              "#C799AE"
              "#2F4E77"
              "#B8385E"
              "#9E4962"])

(def gradient-palette ["#FF0000"
              "#00FF00"
              "#0000FF"])

(defn next-point-color [used-colors]
  (or (some (fn [color] (if (contains? used-colors color) false color)) palette) "#000000"))

(defn move-last
  "Move the first element in coll last. Returns a vector"
  [coll]
  (if (empty? coll)
    coll
    (let [[first & rest] coll]
      (conj (vec rest) first))))

(defn sort-point-color-mapping
  [point-color-mapping]
  (let [sorted (sort-by #(get % "value") point-color-mapping)]
    (if (nil? (-> sorted first (get "value")))
      (move-last sorted)
      sorted)))

(defn point-color-mapping
  [tenant-conn table-name {:strs [pointColorMapping pointColorColumn]} where-clause]
  (when pointColorColumn
    (let [sql-str (format "SELECT distinct %s AS value FROM %s WHERE %s LIMIT 22"
                          pointColorColumn table-name where-clause)
          distinct-values (map :value
                               (jdbc/query tenant-conn sql-str))
          used-colors (set (map #(get % "color") pointColorMapping))
          color-map (reduce (fn [m {:strs [value color]}]
                              (assoc m value color))
                            {}
                            pointColorMapping)
          color-mapping (loop [result []
                               values distinct-values
                               used-colors used-colors]
                          (if (empty? values)
                            result
                            (let [value (first values)]
                              (if-some [color (get color-map value)]
                                (recur (conj result {"op" "equals" "value" value "color" color})
                                       (rest values)
                                       used-colors)
                                (let [color (next-point-color used-colors)]
                                  (recur (conj result {"op" "equals" "value" value "color" color})
                                         (rest values)
                                         (conj used-colors color)))))))]
      (sort-point-color-mapping color-mapping))))

(defn shape-color-mapping [layer]
  [{"op" "heatmap"
    "stop" 0
    "color" "#FFFFFF"}
   {"op" "heatmap"
    "stop" 100
    "color" (if (get layer "gradientColor")
              (get layer "gradientColor")
              (get gradient-palette 0))}])

;; "BOX(-0.127758 51.507351,24.938379 63.095089)"
(defn parse-box [s]
  (let [end (str/index-of s ")")
        box-string (subs s 4 end)
        [left right] (str/split box-string #",")
        [west south] (str/split left #" ")
        [east north] (str/split right #" ")]
    [[(Double/parseDouble south) (Double/parseDouble west)]
     [(Double/parseDouble north) (Double/parseDouble east)]]))

(defn bounds [tenant-conn table-name layer where-clause]
  (let [geom (or (get layer "geom")
                 (format "ST_SetSRID(ST_MakePoint(%s, %s), 4326)"
                         (get layer "longitude")
                         (get layer "latitude")))
        sql-str (format "SELECT ST_Extent(%s) FROM %s WHERE %s"
                        geom table-name where-clause)]
    (when-some [st-extent (-> (jdbc/query tenant-conn sql-str)
                              first :st_extent)]
      (parse-box st-extent))))

(defn get-column-titles [tenant-conn selector-name selector-value]
  (let [sql-str "SELECT columns, modified FROM dataset_version WHERE %s='%s' ORDER BY version DESC LIMIT 1"]
    (map (fn [{:strs [columnName title]}]
           {"columnName" columnName
            "title" title})
         (-> (jdbc/query tenant-conn (format sql-str selector-name selector-value))
             first
             :columns))))

(defn get-column-title-for-name [collection column-name]
  (-> (filter (fn [{:strs [columnName]}]
                (boolean (= columnName column-name)))
              collection)
      first
      (get "title")))

(defn point-metadata [tenant-conn table-name layer where-clause]
  (let [column-titles (get-column-titles tenant-conn "table_name" table-name)]
    {"boundingBox" (bounds tenant-conn table-name layer where-clause)
     "pointColorMapping" (point-color-mapping tenant-conn table-name layer where-clause)
     "availableColors" palette
     "pointColorMappingTitle" (get-column-title-for-name column-titles (get layer "pointColorColumn"))
     "columnTitles" column-titles}))

(defn shape-aggregation-metadata [tenant-conn table-name layer where-clause]
  (let [column-titles (get-column-titles tenant-conn "table_name" table-name)
        column-title-for-name (get-column-title-for-name
                               (get-column-titles tenant-conn "dataset_id"
                                                  (get layer "aggregationDataset"))
                               (get layer "aggregationColumn"))
        shape-color-mapping-title (format "%s (%s)" column-title-for-name
                                          (get layer "aggregationMethod"))]
    {"boundingBox" (bounds tenant-conn table-name layer where-clause)
     "shapeColorMapping" (shape-color-mapping layer)
     "availableColors" gradient-palette
     "columnTitles" column-titles
     "shapeColorMappingTitle" shape-color-mapping-title}))

(defn shape-metadata [tenant-conn table-name layer where-clause]
  (let [column-titles (get-column-titles tenant-conn "table_name" table-name)]
    {"columnTitles" column-titles
     "boundingBox" (bounds tenant-conn table-name layer where-clause)}))

(defn raster-metadata [tenant-conn table-name layer where-clause]
  (let [raster-meta (jdbc/query tenant-conn ["SELECT metadata FROM raster_dataset WHERE raster_table = ?" table-name])
        {{:strs [bbox]} :metadata} (first raster-meta)]
    (if bbox
      {"boundingBox" [(reverse (first bbox)) (reverse (second bbox))]}
      {})))

(defn get-metadata [{:strs [aggregationDataset aggregationColumn aggregationGeomColumn layerType]
                     :as layer}]
  (cond
    (= layerType "raster")
    raster-metadata

    (and aggregationDataset aggregationColumn aggregationGeomColumn)
    shape-aggregation-metadata

    (= layerType "geo-shape")
    shape-metadata

    :else
    point-metadata))

(defn build [tenant-conn table-name layer where-clause]
  ((get-metadata layer) tenant-conn table-name layer where-clause))
