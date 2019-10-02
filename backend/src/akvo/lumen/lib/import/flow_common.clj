(ns akvo.lumen.lib.import.flow-common
  (:require [akvo.commons.psql-util :as pg]
            [cheshire.core :as json]
            [akvo.lumen.http.client :as http.client]
            [clojure.java.jdbc :as jdbc]
            [clojure.string :as str])
  (:import [java.time Instant]))

;; only use this value from a different thread/future
(def ^:private http-client-req-defaults (http.client/req-opts 60000))

(defn survey-definition
  [api-root headers-fn instance survey-id]
  (-> (format "%s/orgs/%s/surveys/%s"
              api-root instance survey-id)
      (http.client/get* (merge http-client-req-defaults {:headers (headers-fn)
                                                         :as :json}))
      :body))

(defn form-instances* [headers-fn url]
  (let [response (-> url
                     (http.client/get* (merge (http.client/req-opts 20000)
                                              {:headers (headers-fn)
                                               :as :json-string-keys}))
                     :body)]
    (lazy-cat (get response "formInstances")
              (when-let [url (get response "nextPageUrl")]
                (form-instances* headers-fn url)))))

(defn form-instances
  "Returns a lazy sequence of form instances"
  [headers-fn form]
  (let [initial-url (str (:formInstancesUrl form) "&page_size=300")]
    (form-instances* headers-fn initial-url)))

(defn data-points*
  [headers-fn url]
  (-> url
      (http.client/get* (merge http-client-req-defaults
                               {:headers (headers-fn)
                                :as :json-string-keys}))
      :body))

(defn data-points
  "Returns all survey data points"
  [headers-fn survey]
  (loop [all-data-points []
         response (data-points* headers-fn
                                (str (:dataPointsUrl survey)
                                     "&page_size=300"))]
    (if-let [url (get response "nextPageUrl")]
      (recur (into all-data-points (get response "dataPoints"))
             (data-points* headers-fn url))
      (into all-data-points (get response "dataPoints")))))

(defn questions
  "Get the list of questions from a form"
  [form]
  (mapcat :questions (:questionGroups form)))

(defn form
  "Get a form by id from a survey"
  [survey form-id]
  (let [form (or (first (filter #(= form-id (:id %)) (:forms survey)))
                 (throw (ex-info "No such form"
                                 {:form-id form-id
                                  :survey-id (:id survey)})))]
    (assoc form
           :registration-form? (= form-id (:registrationFormId survey)))))

;; Transforms the structure
;; {question-group-id -> [{question-id -> response}]
;; to
;; {question-id -> first-response}
(defn question-responses
  "Returns a map from question-id to the first response iteration"
  [responses]
  (->> (vals responses)
       (map first)
       (apply merge)))

(defn commons-columns [form]
  [(cond-> {:title "Identifier" :type "text" :id "identifier"}
     (:registration-form? form) (assoc :key true))
   {:title "Instance id" :type "text" :id "instance_id" :key true}
   {:title "Display name" :type "text" :id "display_name"}
   {:title "Submitter" :type "text" :id "submitter"}
   {:title "Submitted at" :type "date" :id "submitted_at"}
   {:title "Surveyal time" :type "number" :id "surveyal_time"}])

(defn common-records [form-instance data-point]
  {:instance_id   (get form-instance "id")
   :display_name  (get data-point "displayName")
   :identifier    (get data-point "identifier")
   :submitter     (get form-instance "submitter")
   :submitted_at  (some-> (get form-instance "submissionDate") Instant/parse)
   :surveyal_time (get form-instance "surveyalTime")})
