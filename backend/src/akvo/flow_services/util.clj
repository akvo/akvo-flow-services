(ns akvo.flow-services.util
  (:require [akvo.commons.config :as config]
            [aws.sdk.s3 :as s3]
            [clj-http.client :as http]
            [clojure.java.io :as io]
            [akvo.flow-services.error :as e]
            [taoensso.timbre :as timbre])
  (:import [com.fasterxml.jackson.dataformat.xml XmlMapper]
           [java.text SimpleDateFormat]
           [java.util TimeZone Date Base64]
           [java.net URLEncoder]
           [javax.crypto Mac]
           [javax.crypto.spec SecretKeySpec]
           [java.util.zip ZipInputStream]
           [org.akvo.flow.xml XmlForm]))

(defn datastore-spec [app-id-or-bucket]
  (let [cfg (config/find-config app-id-or-bucket)]
    (if (= "this is a hack to force the remote API to use localhost" (:service-account-id cfg))
      {:hostname "localhost"
       :port     8888}
      {:hostname           (:domain cfg)
       :service-account-id (:service-account-id cfg)
       :private-key-file   (:private-key-file cfg)
       :port               443})))


(defn- hmac-shax
  [algorithm secret content opts]
  (let [{:keys [base64]} opts
        m (Mac/getInstance algorithm)
        _ (.init m (SecretKeySpec. (if (= (type secret) String)
                                     (.getBytes ^String secret "UTF-8")
                                     secret)
                                   (.getAlgorithm m)))
        ba (.doFinal m (.getBytes ^String content "UTF-8"))]
    (if base64
      (.encodeToString (Base64/getEncoder) ba)
      ba)))

(defn hmac-sha1
  ([secret content]
   (hmac-sha1 secret content {:base64 true}))
  ([secret content opts]
   (hmac-shax "HmacSHA1" secret content opts)))

(defn hmac-sha256
  ([secret content]
   (hmac-shax "HmacSHa256" secret content {:base64 true}))
  ([secret content opts]
   (hmac-shax "HmacSHA256" secret content opts)))


(defn sign-request-with-timestamp [api-key]
  (let [timestamp (.format (doto (SimpleDateFormat. "yyyy/MM/dd HH:mm:ss")
                             (.setTimeZone (TimeZone/getTimeZone "GMT")))
                           (Date.))
        content (str "ts=" (URLEncoder/encode timestamp "UTF-8"))
        hash (hmac-sha1 api-key content)]
    {:query-params {:ts timestamp
                    :h  hash}}))

(defn api-key-for [job-data]
  (:apiKey (config/find-config (get-in job-data ["opts" "appId"]))))

(defn send-http-json! [job-data request]
  (assert (not (:query-params request)) "Signing just works when request has no params")
  (let [final-request (merge {:as               :json
                              :content-type     :json
                              :throw-exceptions false}
                             (sign-request-with-timestamp (api-key-for job-data))
                             request)
        _ (timbre/debug "request" final-request)
        response (e/wrap-exceptions
                   (http/request final-request))]
    (timbre/debug "response" response)
    response))

(defn get-published-form
  "Retrieve published form from S3"
  [app-id form-id]
  (let [credentials (select-keys (config/find-config app-id) [:access-key :secret-key :s3bucket])
        object-key (str "surveys/" form-id ".zip")]
    (try
      (with-open [in (ZipInputStream. (io/input-stream (:content (s3/get-object credentials (:s3bucket credentials) object-key))))]
        (let [file (.getNextEntry in)]
          (when (= (.getName file) (str form-id ".xml"))
            (.readValue (XmlMapper.) in (class (XmlForm.))))))
      (catch Exception e
        (timbre/errorf "Failed to retrieve form %s" form-id)))))