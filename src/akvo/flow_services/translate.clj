(ns akvo.flow-services.translate
  (:require [clojure.java.io :as io]
            [clojure.set :as set])
  (:import [java.util Properties]))

(def translation-map
  (let [ui-props (Properties.)
        en-props (Properties.)
        es-props (Properties.)]
    (with-open [ui-reader (io/reader "resources/ui-strings.properties")
                en-reader (io/reader "resources/en.properties")
                es-reader (io/reader "resources/es.properties")]
      (.load ui-props ui-reader)
      (.load en-props en-reader)
      (.load es-props es-reader))
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

(comment
  (t> "en" "_report_header")
  (t> "en" "_report_body" "foo")

  (t> "es" "_report_header")
  (t> "es" "_report_body" "foobar")
  )

(defn read-ui-strings []
  (let [props (Properties.)]
    (with-open [r (io/reader "resources/ui-strings.properties")]
      (.load props r)
      (into {} props))))

(defn write-locale-properties [locale ui-strings]
  (let [file (format "resources/%s.properties" locale)
        props (Properties.)]
    (with-open [reader (io/reader file)]
      (.load props reader)
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
    (with-open [writer (io/writer file)]
      (.store props writer nil))))

(comment
  (write-locale-properties "es" (read-ui-strings))
  )
