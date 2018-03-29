;  Copyright (C) 2015 Stichting Akvo (Akvo Foundation)
;
;  This file is part of Akvo FLOW.
;
;  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
;  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
;  either version 3 of the License or any later version.
;
;  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
;  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
;  See the GNU Affero General Public License included below for more details.
;
;  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.

(ns akvo.flow-services.translate
  (:require [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [java.util Properties]))

(def translation-map
  (let [ui-props (Properties.)
        en-props (Properties.)
        es-props (Properties.)]
    (with-open [ui-input-stream (-> "ui-strings.properties" io/resource io/input-stream)
                en-input-stream (-> "en.properties" io/resource io/input-stream)
                es-input-stream (-> "es.properties" io/resource io/input-stream)]
      (.load ui-props ui-input-stream)
      (.load en-props en-input-stream)
      (.load es-props es-input-stream))
    (let [ui-map (into {} ui-props)
          en-map (into {} en-props)
          es-map (into {} es-props)]
      (reduce (fn [result [key val]]
                (-> result
                  (assoc-in ["en" key]
                            (get en-map val))
                  (assoc-in ["es" key]
                            (let [v (get es-map val)]
                              (if (empty? v) nil v)))))
              {}
              ui-map))))

(defn t> [locale key & args]
  (let [s (or (get-in translation-map [locale key])
              (get-in translation-map ["en" key]))]
    (assert s (str "No translation string for key " key))
    (apply format s args)))

(defn read-ui-strings []
  (let [props (Properties.)]
    (with-open [input-stream (-> "ui-strings.properties" io/resource io/input-stream)]
      (.load props input-stream)
      (into {} props))))

(defn write-locale-properties [locale ui-strings]
  (let [resource (io/resource (format "%s.properties" locale))
        props (Properties.)]
    (with-open [input-stream (io/input-stream resource)]
      (.load props input-stream)
      ;; Update keys found in ui-strings.properties
      ;; non-english locales will be initialized with ""
      (doseq [[key value] ui-strings]
        (let [old-value (.getProperty props value "")]
          (if (= old-value "")
            (.setProperty props value (if (= locale "en") value "")))))
      ;; Remove keys not found in ui-strings.properties
      (let [keys (into #{} (.stringPropertyNames props))
            ui-keys (into #{} (vals ui-strings))
            extra-keys (set/difference keys ui-keys)]
        (doseq [key extra-keys]
          (.remove props key))))
    ;; Save the updated <locale>.properties file
    (with-open [writer (io/writer resource)]
      (.store props writer nil))))
