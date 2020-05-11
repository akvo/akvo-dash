(ns akvo.lumen.lib.env
  (:require [akvo.lumen.db.env :as db.env]))

(defn all
  [tenant-conn]
  (let [config (db.env/all-values tenant-conn)]
    (reduce (fn [acc {:keys [id value]}]
              (assoc acc id value))
            {} config)))
