(ns akvo.lumen.lib.dashboard
  (:require [akvo.lumen.lib :as lib]
            [akvo.lumen.auth :as auth]
            [akvo.lumen.util :refer [squuid]]
            [akvo.lumen.lib.visualisation :as vis]
            [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [hugsql.core :as hugsql]))


(hugsql/def-db-fns "akvo/lumen/lib/dashboard.sql")
(hugsql/def-db-fns "akvo/lumen/lib/visualisation.sql")

(defn all [tenant-conn auth-datasets]
  (lib/ok
   (let [all-dashs (all-dashboards tenant-conn)]
     (if (seq all-dashs)
       (let [dash-ids               (map :id all-dashs)
             vizs-in-dash           (visualisations-by-dashboard-visualisation-id-list tenant-conn {:ids dash-ids})
             auth-dash-ids-set      (let [auth-vis* (partial vis/auth-vis auth-datasets)]
                                      (->> vizs-in-dash
                                           (filter auth-vis*)
                                           (map :dashboard_id)
                                           set))
             dashs-wiht-no-viss-set (set/difference (set dash-ids)
                                                    (set (map :dashboard_id vizs-in-dash)))
             filtered               (apply conj dashs-wiht-no-viss-set auth-dash-ids-set)]
         (filter #(contains? filtered (:id %)) all-dashs))
       []))))

(defn- dashboard-keys-match?
  "Make sure each key in entity have a matching key in layout."
  [dashboard]
  (= (keys (get dashboard "entities"))
     (keys (get dashboard "layout"))))

(defn filter-type
  [dashboard-data kind]
  (let [entities (filter #(= kind (get % "type"))
                         (vals (get dashboard-data "entities")))
        ks     (set (map #(get % "id") entities))]
    {:entities entities
     :layout   (keep #(if (ks (get % "i")) %)
                     (vals (get dashboard-data "layout")))}))

(defn- part-by-entity-type [entities]
  {:visualisations (filter-type entities "visualisation")
   :texts          (filter-type entities "text")})

(defn- all-entities
  "Merge text & dashboard_visualisations (dvs) entries, return an id keyed map"
  [text-entities dvs]
  (let [entries (reduce conj text-entities (map (fn [dv]
                                                  {"id"   (:visualisation_id dv)
                                                   "type" "visualisation"}) dvs ))]
    (zipmap (map #(get % "id") entries)
            entries)))

(defn- all-layouts
  "Merge text & dashboard_visualisations (dvs) layouts, return an id keyed map."
  [text-layout dvs]
  (let [layouts (reduce conj text-layout (map (fn [dv]
                                                (:layout dv))
                                              dvs))]
    (zipmap (map #(get % "i") layouts)
            layouts)))

(defn- build-dashboard-by-id
  ""
  [dashboard dvs]
  (assoc (select-keys dashboard [:author :created :id :modified :title])
         :entities (all-entities (get-in dashboard [:spec "entities"]) dvs)
         :layout (all-layouts (get-in dashboard [:spec "layout"]) dvs)
         :type "dashboard"
         :status "OK"))

(defn- handle-dashboard-by-id
  "Hand of packing to pure build-dashboard-by-id"
  [tenant-conn id]
  (build-dashboard-by-id
   (dashboard-by-id tenant-conn {:id id})
   (dashboard_visualisation-by-dashboard-id tenant-conn
                                            {:dashboard-id id})))

(defn create
  "With a dashboard spec, first split into visualisation and text entities.
  Insert new dashboard and pass all text entites into the dashboard.spec. Then
  for each visualiation included in the dashboard insert an entry into
  dashboard_visualisation with it's layout data."
  [tenant-conn spec claims auth-datasets]
  (log/error :spec spec :auth-dss auth-datasets)
  (if (dashboard-keys-match? spec)
    (let [dashboard-id (str (squuid))
          parted-spec (part-by-entity-type spec)

          visualisation-layouts (get-in parted-spec [:visualisations :layout])]
      (if (let [auth-vis* (partial vis/auth-vis auth-datasets)]
            (every? auth-vis* (visualisation-by-id-list tenant-conn {:ids (map #(get % "id") (:entities (:visualisations parted-spec)))})))
        (do
          (jdbc/with-db-transaction [tx tenant-conn]
            (insert-dashboard tx {:id dashboard-id
                                  :title (get spec "title")
                                  :spec (:texts parted-spec)
                                  :author claims})
            (doseq [visualisation (get-in parted-spec [:visualisations :entities])]
              (let [visualisation-id (get visualisation "id")
                    layout (first (filter #(= visualisation-id (get % "i"))
                                          visualisation-layouts))]
                (insert-dashboard_visualisation
                 tx {:dashboard-id dashboard-id
                     :visualisation-id visualisation-id
                     :layout layout}))))
          (lib/ok (handle-dashboard-by-id tenant-conn dashboard-id)))
        auth/not-authorized))
    (lib/bad-request {:error "Entities and layout dashboard keys does not match."})))

(defn fetch [tenant-conn id auth-datasets]
  (if-let [d (dashboard-by-id tenant-conn {:id id})]
    (lib/ok (handle-dashboard-by-id tenant-conn id))
    (lib/not-found {:error "Not found"})))

(defn upsert
  "We update a dashboard via upsert of dashboard and clean - insert of
  dashboard_visualisations.
  1. Unpack text & visualisation entities
  2. Update dashboard tablefq
  3. Remove old dashboard_visualisations
  4. Add new dashboard_visualisations
  "
  [tenant-conn id spec auth-datasets]
  (let [{:keys [texts visualisations]} (part-by-entity-type spec)
        visualisations-layouts (:layout visualisations)]
    (jdbc/with-db-transaction [tx tenant-conn]
      (update-dashboard tx {:id id
                            :title (get spec "title")
                            :spec texts})
      (delete-dashboard_visualisation tenant-conn {:dashboard-id id})
      (doseq [visualisation-entity (:entities visualisations)]
        (let [visualisation-id (get visualisation-entity "id")
              visualisations-layout (first (filter #(= visualisation-id
                                                       (get % "i"))
                                                   visualisations-layouts))]
          (insert-dashboard_visualisation
           tx {:dashboard-id id
               :visualisation-id visualisation-id
               :layout visualisations-layout}))))
    (lib/ok (handle-dashboard-by-id tenant-conn id))))

(defn delete [tenant-conn id auth-datasets]
  (delete-dashboard_visualisation tenant-conn {:dashboard-id id})
  (if (zero? (delete-dashboard-by-id tenant-conn {:id id}))
    (lib/not-found {:error "Not round"})
    (lib/ok {:id id})))
