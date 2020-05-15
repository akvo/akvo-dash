(ns akvo.lumen.lib.transformation.derive.js-engine
  (:require
   [akvo.lumen.lib.transformation.engine :as engine]
   [clojure.edn :as edn]
   [clojure.java.jdbc :as jdbc]
   [clojure.set :as set]
   [clojure.spec.alpha :as s]
   [clojure.string :as str]
   [clojure.string :as string]
   [clojure.tools.logging :as log]
   [cheshire.core :as json])
  (:import [javax.script ScriptEngineManager ScriptEngine Invocable ScriptContext Bindings]
           [jdk.nashorn.api.scripting NashornScriptEngineFactory ClassFilter ScriptObjectMirror]
           [java.lang Double]))

(defn- throw-invalid-return-type [value]
  (throw (ex-info "Invalid return type"
                  {:value value
                   :type (type value)})))

(defn- column-function [fun code]
  (format "var %s = function(row) {if (row.__rqg__) { row.__rqg__.forEach((e) => { row[e] = JSON.parse(row[e]); })}; return %s; }" fun code))

(defn- valid-type? [value t]
  (when-not (nil? value)
    (condp = t
      "number" (if (and (number? value)
                        (if (float? value)
                          (Double/isFinite value)
                          true))
                 (Double/parseDouble (format "%.3f" (double value)))
                 (throw-invalid-return-type value))
      "text" (if (string? value)
               value
               (throw-invalid-return-type value))
      "date" (cond
               (number? value)
               (java.sql.Timestamp. (long value))

               (and (instance? jdk.nashorn.api.scripting.ScriptObjectMirror value)
                    (.containsKey value "getTime"))
               (java.sql.Timestamp. (long (.callMember value "getTime" (object-array 0))))

               :else
               (throw-invalid-return-type value)))))

(def ^ClassFilter class-filter
  (reify ClassFilter
    (exposeToScripts [this s]
      false)))

(defn- remove-bindings [^Bindings bindings]
  (doseq [function ["print" "load" "loadWithNewGlobal" "exit" "quit" "eval"]]
    (.remove bindings function)))

(defn column-name->column-title
  "replace column-name by column-title"
  [columns]
  (let [key-translation (->> columns
                             (map (fn [{:strs [columnName title]}]
                                    [(keyword columnName) title]))
                             (into {}))]
    #(clojure.set/rename-keys % key-translation)))

(defn rqg
  [columns]
  (fn [row]
    (let [cols (->> columns
                    (filter #(= (get % "type") "rqg"))
                    (mapv #(get % "title")))]
      (assoc row "__rqg__" cols))))

(defn- js-factory [] (NashornScriptEngineFactory.))

(defn nashorn-deprecated? []
  (>= (-> (System/getProperty "java.version")
          (string/split #"\.")
          first
          edn/read-string)
      11))


(defn script-engine [factory]
  (if (nashorn-deprecated?)
    (.getScriptEngine factory
                      (into-array String ["--no-deprecation-warning" "--language=es6"])
                      nil class-filter)
    (.getScriptEngine factory class-filter)))

(defn- js-engine
  ([]
   (js-engine (js-factory)))
  ([factory]
   (let [engine (script-engine factory)]
     (remove-bindings (.getBindings engine ScriptContext/ENGINE_SCOPE))
     engine)))

(defn eval*
  ([^String code]
   (eval* (js-engine) code))
  ([^ScriptEngine engine ^String code]
   (.eval ^ScriptEngine engine ^String code)))

(defn- invoke* [^Invocable engine ^String fun & args]
  (.invokeFunction engine fun (object-array args)))


(defn row-transform-fn
  [{:keys [adapter code column-type]}]
  (let [engine (js-engine)
        fun-name "deriveColumn"]
    (eval* engine (column-function fun-name code))
    (fn [row]
      (let [res (->> row
                     (adapter)
                     (java.util.HashMap.)
                     (invoke* engine fun-name))]
        (if (some? column-type)
            (valid-type? res column-type)
            res)))))

(defn- parse [^String code]
  (let [factory (js-factory)
        engine (.getScriptEngine factory (into-array String ["--no-deprecation-warning" "--language=es6"]))]
    (eval* engine "load('nashorn:parser.js')")
    (.put engine "source_code" code)
    (json/parse-string
     (eval* engine "JSON.stringify(parse(source_code))"))))

(defn evaluable? [^String code]
  (try
    (let [modified (.replaceAll code " " "")]
      (and (not (.contains modified "while(true)"))
           (not (.contains modified "for(;;)"))
           (= "ExpressionStatement"
              (-> (parse code)
                  (get "body")
                  (nth 0)
                  (get "type")))))
    (catch Exception e
      (log/warn :not-valid-js code)
      false)))
