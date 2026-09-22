(ns akvo.flow-services.test-util
  (:require [clj-http.client :as http]
            [akvo.flow-services.core :as core]
            [aero.core :as aero]
            [cheshire.core :as json]
            [clojure.test :as clj-test]
            [akvo.commons.config :as config])
  (:import (java.net Socket)))

(def wiremock-url "http://wiremock-proxy:8080")
(def wiremock-mappings-url (str wiremock-url "/__admin/mappings"))
;; Mailpit is a real SMTP server rather than a stub, so notifications are not
;; mocked any more -- they are delivered and then read back off this API.
(def mailpit-url "http://mailpit:8025")
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

(defn mock-sentry []
  (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"  "POST"
                                                                             "urlPath" "/sentry/api/213123/store/"}
                                                                 "response" {"status" 200
                                                                             "body"   "ok"}})}))

(defn mock-flow-report-api []
  (http/post wiremock-mappings-url
             {:body (json/generate-string
                      {"request"  {"method"         "PUT"
                                   "urlPathPattern" "/rest/reports/*"}
                       "response" {"status"   200
                                   "jsonBody" {}}})}))

(defn text-first-email-sent-to
  "Plain-text body of the first message Mailpit holds for `email`, or nil.

  Mailpit's search returns message summaries without their bodies, so the body
  takes a second call. Every test address here is unique to its test, so \"first\"
  is unambiguous."
  [email]
  (when-let [id (-> (http/get (str mailpit-url "/api/v1/search")
                              {:as :json :query-params {"query" (str "to:" email)}})
                    :body
                    :messages
                    first
                    :ID)]
    (-> (http/get (str mailpit-url "/api/v1/message/" id) {:as :json})
        :body
        :Text)))

(defn reset-wiremock []
  (http/post (str wiremock-mappings-url "/reset")))

(defn reset-mailpit []
  (http/delete (str mailpit-url "/api/v1/messages")))

(defn get-report [report-result]
  (http/get (str flow-services-url "/report/" (get report-result "file"))))

(defn setup-wiremock [messages]
  (doseq [message messages]
    (http/post wiremock-mappings-url {:body (json/generate-string message)})))

(defn check-servers-up []
  (wait-for-server "localhost" 3000)
  (wait-for-server "localhost" 8888)
  (wait-for-server "wiremock-proxy" 8080)
  (wait-for-server "mailpit" 1025))

(defn fixture [f]
  (let [config (aero/read-config "dev/config.edn")]
    (core/config-logging config)
    (when (empty? @config/settings)
      (reset! config/settings config)
      (config/set-config! "dev/flow-server-config/")))
  (check-servers-up)
  (reset-wiremock)
  (reset-mailpit)
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

(defn check-report-file-headers [report-result expected-content-type]
  (let [headers (:headers (get-report report-result))]
    (clj-test/is (= expected-content-type (get headers "Content-Type")))
    (clj-test/is (= "attachment" (get headers "Content-Disposition")))))
