(ns datacleaning.flow
  (:require [clojure.java.jdbc :as jdbc]
            [cheshire.core :as json])
  (:import [org.postgresql.util PGobject]))

;; TODO: copied from akvo-commons
(defn val->jsonb-pgobj
  [v]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/generate-string v))))

(extend-protocol jdbc/ISQLValue
  clojure.lang.IPersistentMap
  (sql-value [v] (val->jsonb-pgobj v))

  clojure.lang.IPersistentVector
  (sql-value [v] (val->jsonb-pgobj v)))


;; From json & jsonb

(defn pgobj->val
  [^PGobject pgobj]
  (let [t (.getType pgobj)
        v (.getValue pgobj)]
    (case t
      "json"  (json/parse-string v)
      "jsonb" (json/parse-string v)
      :else   v)))

(extend-protocol jdbc/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj _ _]
    (pgobj->val pgobj)))

(set! *warn-on-reflection* true)
(set! *print-length* 20)

(defn survey-definition [org-id survey-id]
  (let [conn (str "jdbc:postgresql://localhost/" org-id)
        survey (first (jdbc/query conn ["SELECT * FROM survey where id=?" survey-id]))
        forms (jdbc/query conn ["select * from form where survey_id=?" survey-id])
        question-groups (jdbc/query conn
                                    [(print-str "select question_group.*"
                                                "from question_group, form"
                                                "where question_group.form_id = form.id and"
                                                "form.survey_id=?")
                                     survey-id])
        questions (jdbc/query conn
                              [(print-str "select question.*"
                                          "from question, question_group, form"
                                          "where question_group.form_id = form.id and"
                                          "question.question_group_id = question_group.id and"
                                          "form.survey_id=?")
                               survey-id])
        questions (group-by :question_group_id questions)
        question-groups (for [{:keys [id] :as question-group} (sort-by :display_order question-groups)]
                          (assoc question-group
                                 :questions
                                 (vec (sort-by :display_order (get questions id)))))
        question-groups (group-by :form_id question-groups)
        forms (for [form forms]
                (assoc form :question-groups (get question-groups (:id form))))]
    (assoc survey :forms (into {} (map (juxt :id identity)) forms))))


(comment (time (survey-definition "akvoflow-uat1" 10079120)) )

(defn response-index
  "Index a sequence of responses in form-instance-id question-id iteration order."
  [responses]
  (reduce (fn [index {:keys [form_instance_id question_id iteration] :as response}]
            (assoc-in index [form_instance_id question_id iteration] response))
          {}
          responses))

(defn form-instances-index
  "Index a sequence of form-instances in data_point_id and form_id order"
  [form-instances responses]
  (reduce (fn [index {:keys [id form_id data_point_id] :as form-instance}]
            (assoc-in index [data_point_id form_id] (assoc form-instance :responses (get responses id))))
          {}
          form-instances))

(defn survey-data-points [org-id survey-id]
  (let [conn (str "jdbc:postgresql://localhost/" org-id)
        data-points (jdbc/query conn ["SELECT * FROM data_point where survey_id=?" survey-id])
        form-instances (jdbc/query conn
                                   [(print-str "select form_instance.* from form_instance, form"
                                               "where form_instance.form_id=form.id and"
                                               "form.survey_id=?")
                                    survey-id])
        responses (jdbc/query conn
                              [(print-str "select response.* from response, form_instance, form"
                                          "where response.form_instance_id=form_instance.id and"
                                          "form_instance.form_id=form.id and"
                                          "form.survey_id=?")
                               survey-id])
        responses (response-index responses)
        form-instances (form-instances-index form-instances responses)]
    (for [{:keys [id] :as data-point} data-points]
      (assoc data-point :form-instances (get form-instances id)))))

;; =======

(defn dataset-columns [form]
  (concat [{:name "Identifier" :type "string"}
           {:name "Latitude" :type "number"}
           {:name "Longitude" :type "number"}
           {:name "Submitter" :type "string"}
           {:name "Submitted at" :type "date"}]
          (mapcat (comp #(map (fn [question]
                                {:name (:display_text question)
                                 :type "string"})
                              %)
                        :questions)
                  (:question-groups form))))

(defn dataset-data [data-points format-responses]
  (for [data-point data-points
        :let [form-instance (get-in data-point [:form-instances form-id])]
        :when form-instance]
    (reduce into
            ((juxt :identifier :latitude :longitude) data-point)
            [((juxt :sumbitter :sumbitted_at) form-instance)
             (format-responses form-instance)])))

;; (count (survey-data-points "akvoflow-uat1" 5889121))
;; (survey-data-points "akvoflow-uat1" 10079120)
;; (get-survey-data 10079120)

(defn format-responses-fn [form]
  (let [question-ids (mapcat (comp #(map :id %) :questions)
                             (:question-groups form))]
    (fn [form-instance]
      (mapv (fn [question-id]
              (json/generate-string (get-in form-instance
                                            [:responses question-id 0 :value "value"])))
            question-ids))))

(defn uuid []
  (str (java.util.UUID/randomUUID)))

(defn create-data-table [table-name column-names]
  (format "create table %s (%s);"
          table-name
          (str/join ", " (map #(str % " jsonb") column-names))))

(defn insert-dataset-columns! [conn dataset-columns column-names]
  (apply jdbc/insert!
         conn
         :dataset_column
         (map-indexed
          (fn [idx column]
            (assoc column :c_order idx))
          (map (fn [column-name column]
                 (merge column
                        {:c_name column-name
                         :dataset_id dataset-id}))
               column-names
               dataset-columns))))

(defn insert-dataset-version! [conn dataset-id table-name]
  (jdbc/insert! conn
                :dataset_version
                {:dataset_id dataset-id
                 :table_name table-name
                 :version 0}))

(defn insert-dataset-data! [conn dataset-data column-names]
  (apply jdbc/insert!
         conn
         table-name
         (map (fn [data-row]
                (into {} (map vector
                              column-names
                              (map  val->jsonb-pgobj data-row) )))
              dataset-data)))


(defn create-dataset [org-id survey-id form-id]
  (let [conn (str "jdbc:postgresql://localhost/" org-id)
        survey (survey-definition org-id survey-id)
        form (get-in survey [:forms form-id])
        format-responses (format-responses-fn form)
        data-points (survey-data-points org-id survey-id)
        dataset-columns (dataset-columns form)
        column-count (count dataset-columns)
        dataset-data (dataset-data data-points format-responses)
        table-name (str "t_" (str/replace (uuid) "-" ""))
        dataset-id (:id (first (jdbc/insert! conn :dataset (select-keys dataset [:name]))))
        column-names (map #(str "c" %) (range))]
    (insert-dataset-columns! conn dataset-columns column-names)
    (insert-dataset-version! conn dataset-id table-name)
    (jdbc/execute! conn [(create-data-table table-name (take column-count column-names))])
    (insert-dataset-data! dataset-data column-names)))

;; (create-dataset "akvoflow-uat1" 10079120 7169115)
