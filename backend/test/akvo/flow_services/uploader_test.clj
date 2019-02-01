(ns akvo.flow-services.uploader-test
  (:require [clojure.test :refer :all]
            [me.raynes.fs.compression :as fsc]
            [akvo.flow-services.uploader :as uploader])
  (:import (java.io File)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(defn as-byte-array [f]
  (let [ary (byte-array (.length f))
        is (java.io.FileInputStream. f)]
    (.read is ary)
    (.close is)
    ary))

(defn temp-dir []
  (doto
    (Files/createTempDirectory "flow-services-test" (into-array FileAttribute []))
    (.. toFile deleteOnExit)))

(defn temp-zip-file []
  (doto
    (File/createTempFile "flow-services-test" ".zip" (.toFile (temp-dir)))
    .deleteOnExit))

(defn zip-with-one-datapoint []
  (let [f (temp-zip-file)]
    (fsc/zip f ["data.json" (.getAbsolutePath f)])
    f))

(defn zip-with [& files]
  (let [zip-with-zip (temp-zip-file)
        contents (map (fn [file]
                        [(.getName file) (as-byte-array file)])
                   files)]
    (fsc/zip zip-with-zip contents)
    zip-with-zip))

(defn file-names [files]
  (set (map #(.getName %) files)))

(defn upload [zip-file]
  (:upload-and-notify
    (uploader/calc-bulk-survey
      (.getAbsolutePath (.getParentFile zip-file))
      (.getName zip-file))))

(deftest bulk-upload
  (testing "Bulk file with several datapoints"
    (let [files [(zip-with-one-datapoint) (zip-with-one-datapoint) (zip-with-one-datapoint)]
          bulk-zip (apply zip-with files)]
      (is (=
            (file-names files)
            (file-names (upload bulk-zip))))))
  (testing "user failed, but we are lenient"
    (let [user-fail (zip-with-one-datapoint)]
      (is (=
            (file-names [user-fail])
            (file-names (upload user-fail)))))))