(ns akvo.flow-services.uploader-test
  (:require [clojure.test :refer :all]
            [clojure.string :as str]
            [clj-http.client :as http]
            [akvo.flow-services.util :as util]
            [me.raynes.fs.compression :as fsc]
            [akvo.flow-services.uploader :as uploader])
  (:import (java.io File)
           (java.net URLEncoder)
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

(defn zip-with-no-datapoints []
  (let [f (temp-zip-file)]
    (fsc/zip f ["invalid.file.txt" (.getAbsolutePath f)])
    f))

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
  (uploader/calc-bulk-survey
    (.getAbsolutePath (.getParentFile zip-file))
    (.getName zip-file)))

(defn assert-upload [uploaded-file expected-files]
  (let [upload-result (upload uploaded-file)]
    (is (=
          (file-names expected-files)
          (file-names (:upload-and-notify upload-result))))
    (is (str/includes?
          (:user-message upload-result)
          (str "Uploaded " (count expected-files) " data files")))))

(deftest bulk-upload
  (testing "Bulk file with several datapoints"
    (let [files [(zip-with-one-datapoint) (zip-with-one-datapoint) (zip-with-one-datapoint)]
          bulk-zip (apply zip-with files)]
      (assert-upload bulk-zip files)))

  (testing "user failed, but we are lenient"
    (let [user-fail (zip-with-one-datapoint)]
      (assert-upload user-fail [user-fail])))

  (testing "no data should result in an error"
    (let [no-data (zip-with-no-datapoints)]
      (is (str/includes?
            (:user-message (upload no-data))
            "no data found")))))

(deftest upload-image-signs-request
  (testing "upload-image attaches ts/h query params signed with the instance api key"
    (let [captured (atom nil)
          img (doto (File/createTempFile "img" ".jpg") .deleteOnExit)]
      (with-redefs [http/post (fn [url opts]
                                (reset! captured {:url url :opts opts})
                                {:status 200})]
        (#'uploader/upload-image "http://backend" "very private" 222 111 img))
      (let [qp (get-in @captured [:opts :query-params])]
        (is (contains? qp :ts) "sends a ts param")
        (is (contains? qp :h) "sends an h param")
        ;; h must be HmacSHA1/Base64 over "ts=<url-encoded-ts>" — exactly what RestAuthFilter recomputes
        (is (= (:h qp)
               (util/hmac-sha1 "very private"
                               (str "ts=" (URLEncoder/encode (:ts qp) "UTF-8")))))))))