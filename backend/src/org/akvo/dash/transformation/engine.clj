(ns org.akvo.dash.transformation.engine
  (:import [javax.script ScriptEngine ScriptEngineManager])
  (:require [clojure.java.jdbc :as jdbc]))

(defn get-engine
  "Returns a Nashorn engine capable of evaluating
  JavaScript code"
  []
  (->
   (ScriptEngineManager.)
   (.getEngineByName "nashorn")))

(defn js-eval
  [engine expr]
  (.eval ^ScriptEngine engine expr))

(defn to-number
  [engine value]
  (js-eval engine (str "Number(" value ")")))

(defn to-string
  [engine value]
  (js-eval engine (str "''+" value)))

(defn to-lowercase
  [engine value]
  (js-eval engine (str "'" value "'.toLowerCase()")))

(defn to-uppercase
  [engine value]
  (js-eval engine (str "'" value "'.toUpperCase()")))
