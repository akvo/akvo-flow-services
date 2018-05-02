(ns akvo.flow-services.util
  (:require [akvo.commons.config :as config]
            [clj-http.client :as http]
            [akvo.flow-services.error :as e])
  (:import (java.text SimpleDateFormat)
           (java.util TimeZone Date)
           (java.net URLEncoder)
           (javax.crypto Mac)
           (javax.crypto.spec SecretKeySpec)
           (com.google.gdata.util.common.util Base64)))

(defn datastore-spec [app-id-or-bucket]
  (let [cfg (config/find-config app-id-or-bucket)]
    (if (= "this is a hack to force the remote API to use localhost" (:service-account-id cfg))
      {:hostname           "localhost"
       :port               8888}
      {:hostname           (:domain cfg)
       :service-account-id (:service-account-id cfg)
       :private-key-file   (:private-key-file cfg)
       :port               443})))

(defn sign-request-with-timestamp [api-key]
  (let [timestamp (.format (doto (SimpleDateFormat. "yyyy/MM/dd HH:mm:ss")
                             (.setTimeZone (TimeZone/getTimeZone "GMT")))
                           (Date.))
        content (str "ts=" (URLEncoder/encode timestamp "UTF-8"))
        m (Mac/getInstance "HmacSHA1")
        _ (.init m (SecretKeySpec. (.getBytes api-key)
                                   (.getAlgorithm m)))
        hash (Base64/encode (.doFinal m (.getBytes content)))]
    {:query-params {:ts timestamp
                    :h  hash}}))

(defn api-key-for [job-data]
  (:apiKey (config/find-config (get-in job-data ["opts" "appId"]))))

(defn send-http-json! [job-data request]
  (assert (not (:query-params request)) "Signing just works when request has no params")
  (e/wrap-exceptions
    (http/request (merge {:as               :json
                          :content-type     :json
                          :throw-exceptions false}
                         (sign-request-with-timestamp (api-key-for job-data))
                         request))))