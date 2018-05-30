(ns akvo.flow-services.test-util
  (:require [clj-http.client :as http]
            [akvo.flow-services.core :as core]
            [aero.core :as aero]
            [cheshire.core :as json])
  (:import (java.net Socket)))

(def wiremock-url "http://wiremock-proxy:8080")
(def wiremock-mappings-url (str wiremock-url "/__admin/mappings"))
(def flow-services-url "http://localhost:3000")
(def gae-local {:hostname "localhost"
                :port     8888})

(defmacro try-for [msg how-long & body]
  `(let [start-time# (System/currentTimeMillis)]
     (loop []
       (let [[status# return#] (try
                                 (let [result# (do ~@body)]
                                   [(if result# ::ok ::fail) result#])
                                 (catch Throwable e# [::error e#]))
             more-time# (> (* ~how-long 1000)
                           (- (System/currentTimeMillis) start-time#))]
         (cond
           (= status# ::ok) return#
           more-time# (do (Thread/sleep 1000) (recur))
           (= status# ::fail) (throw (ex-info (str "Failed: " ~msg) {:last-result return#}))
           (= status# ::error) (throw (RuntimeException. (str "Failed: " ~msg) return#)))))))

(defn wait-for-server [host port]
  (try-for (str "Nobody listening at " host ":" port) 60
           (with-open [_ (Socket. host (int port))]
             true)))

(defn mock-mailjet []
  (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"  "POST"
                                                                             "urlPath" "/mailjet/send"}
                                                                 "response" {"status" 200
                                                                             "body"   "ok"}})}))

(defn email-sent-count [body]
  (-> (http/post (str wiremock-url "/__admin/requests/count")
                 {:as   :json
                  :body (json/generate-string
                          {"method"       "POST"
                           "bodyPatterns" [{"matches" (str ".*" body ".*")}]
                           "urlPath"      "/mailjet/send"})})
      :body
      :count))

(defn reset-wiremock []
  (http/post (str wiremock-mappings-url "/reset")))

(defn get-report [report-result]
  (http/get (str flow-services-url "/report/" (get report-result "file"))))

(defn setup-wiremock [messages]
  (doseq [message messages]
    (http/post wiremock-mappings-url {:body (json/generate-string message)})))

(defn check-servers-up []
  (wait-for-server "localhost" 3000)
  (wait-for-server "localhost" 8888)
  (wait-for-server "wiremock-proxy" 8080))

(defn fixture [f]
  (core/config-logging (aero/read-config "dev/config.edn"))
  (check-servers-up)
  (reset-wiremock)
  (f))

(defn instance-data [survey-id instance-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/instancedata"
               "queryParameters" {"action"           {"equalTo" "getInstanceData"}
                                  "surveyInstanceId" {"equalTo" (str instance-id)}}}
   "response" {"status"   200
               "jsonBody" {"surveyInstanceData"   {"surveyedLocaleDisplayName" "a survey display name"
                                                   "surveyId"                  survey-id
                                                   "surveyedLocaleId"          147442028
                                                   "userID"                    1
                                                   "collectionDate"            1418598306000
                                                   "submitterName"             "BASH & JAMES"
                                                   "deviceIdentifier"          "IMPORTER"
                                                   "surveyalTime"              0
                                                   "surveyedLocaleIdentifier"  "a6ey-5r1h-6a0y"
                                                   "keyId"                     instance-id}
                           "latestApprovalStatus" ""
                           "resultCount"          0
                           "offset"               0}}})