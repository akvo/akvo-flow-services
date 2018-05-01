(ns akvo.flow-services.end-to-end-test
  {:integration true}
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [akvo.flow-services.test-util :as test-util]
            [clojure.string :as str]
            [taoensso.timbre :as timbre])
  (:import java.util.Base64))

(when-let [log-level (System/getenv "LOG_LEVEL")]
  (timbre/set-level! (keyword log-level)))

(defn encode [to-encode]
  (.encodeToString (Base64/getEncoder) (.getBytes to-encode)))

(def wiremock-url "http://wiremock-proxy:8080")
(def wiremock-mappings-url (str wiremock-url "/__admin/mappings"))
(def flow-services-url "http://mainnetwork:3000")

(defn generate-report [survey-id & [opts]]
  (some->> (http/get (str flow-services-url "/generate")
                     {:query-params {:callback "somejson"
                                     :criteria (json/generate-string
                                                 {"baseURL"    wiremock-url
                                                  "exportType" "DATA_CLEANING"
                                                  "surveyId"   (str survey-id)
                                                  "id"         "asdf"
                                                  "opts"       (merge {"email"          "dan@akvo.org"
                                                                       "lastCollection" "false"
                                                                       "uploadUrl"      "https://flowservices-dev-config.s3.amazonaws.com"
                                                                       "exportMode"     "DATA_CLEANING"
                                                                       "from"           "2013-03-06"
                                                                       "to"             "2018-03-09"
                                                                       "flowServices"   flow-services-url
                                                                       "appId"          "flowservices-dev-config"}
                                                                      opts)})}})
           :body
           (re-seq #"somejson\((.*)\);")
           first
           second
           json/parse-string))

(defn survey-rest-api [action survey-id dto-list]
  {"request"  {"method"          "GET"
               "urlPath"         "/surveyrestapi"
               "queryParameters" {"action"   {"equalTo" action}
                                  "surveyId" {"equalTo" (str survey-id)}}}
   "response" {"status"   200
               "jsonBody" {"dtoList"     dto-list
                           "resultCount" 0
                           "offset"      0}}})

(defn gae-survey-group [survey-id]
  (survey-rest-api "getSurveyGroup"
                   survey-id
                   [{"newLocaleSurveyId"   survey-id
                     "description"         ""
                     "createdDateTime"     1490605551743
                     "lastUpdateDateTime"  1490605647506
                     "ancestorIds"         [0 153142013]
                     "monitoringGroup"     true
                     "parentId"            153142013
                     "projectType"         "PROJECT"
                     "defaultLanguageCode" "en"
                     "privacyLevel"        "PUBLIC"
                     "published"           false
                     "requireDataApproval" false
                     "code"                "NR-handpump"
                     "keyId"               148412306
                     "name"                "NR-handpump"
                     "path"                "/Folder with a few large data sets/NR-handpump"}]))

(defn gae-list-survey-questions [survey-id question-group-id question-id]
  (survey-rest-api "listSurveyQuestions" survey-id [{"keyId"                 question-id
                                                     "surveyId"              survey-id
                                                     "questionGroupId"       question-group-id
                                                     "text"                  "Location"
                                                     "order"                 1
                                                     "allowDecimal"          false
                                                     "allowSign"             false
                                                     "dependentFlag"         false
                                                     "allowMultipleFlag"     false
                                                     "allowOtherFlag"        false
                                                     "mandatoryFlag"         true
                                                     "tip"                   "Some GEO question"
                                                     "collapseable"          false
                                                     "immutable"             false
                                                     "geoLocked"             true
                                                     "allowExternalSources"  false
                                                     "localeNameFlag"        false
                                                     "requireDoubleEntry"    false
                                                     "localeLocationFlag"    false
                                                     "variableName"          "location"
                                                     "allowPoints"           false
                                                     "allowLine"             false
                                                     "allowPolygon"          false
                                                     "caddisflyResourceUuid" "nil"
                                                     "type"                  "GEO"
                                                     "path"                  ""}]))

(defn gae-list-groups [survey-id question-group-id]
  (survey-rest-api "listGroups" survey-id [{"surveyId"    survey-id
                                            "keyId"       question-group-id
                                            "order"       2
                                            "repeatable"  false
                                            "code"        "Asset data"
                                            "name"        "Asset data"
                                            "path"        ""
                                            "displayName" "Asset data"}]))

(defn gae-list-instances [survey-id instance-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/databackout"
               "queryParameters" {"action"   {"equalTo" "listInstance"}
                                  "surveyId" {"equalTo" (str survey-id)}}}
   "response" {"status" 200
               "body"   (str instance-id "|14-12-2014 23:05:06 UTC")}})

(defn instance-responses [instance-id question-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/databackout"
               "queryParameters" {"action"           {"equalTo" "listInstanceResponse"}
                                  "surveyInstanceId" {"equalTo" (str instance-id)}}}
   "response" {"status" 200
               "body"   (str question-id ",0," (encode "https://akvoflow-14.s3.amazonaws.com/images/wfpPhoto8526862486761.jpg|Borehole|By The Chuch|AfriDev") "\n")}})

(defn instance-data [survey-id instance-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/instancedata"
               "queryParameters" {"action"           {"equalTo" "getInstanceData"}
                                  "surveyInstanceId" {"equalTo" (str instance-id)}}}
   "response" {"status"   200
               "jsonBody" {"surveyInstanceData"   {"surveyedLocaleDisplayName" ""
                                                   "surveyId"                  survey-id
                                                   "surveyedLocaleId"          147442028
                                                   "userID"                    1 "collectionDate" 1418598306000
                                                   "submitterName"             "BASH & JAMES"
                                                   "deviceIdentifier"          "IMPORTER"
                                                   "surveyalTime"              0
                                                   "surveyedLocaleIdentifier"  "a6ey-5r1h-6a0y"
                                                   "keyId"                     instance-id}
                           "latestApprovalStatus" ""
                           "resultCount"          0
                           "offset"               0}}})

(defn mock-gae [survey-id]
  (let [question-group-id 148442015
        question-id 144672013
        instance-id 144672047
        messages [(gae-survey-group survey-id)
                  (gae-list-survey-questions survey-id question-group-id question-id)
                  (survey-rest-api "listSurveyQuestionOptions" survey-id [])
                  (gae-list-groups survey-id question-group-id)

                  (gae-list-instances survey-id instance-id)
                  (instance-responses instance-id question-id)
                  (instance-data survey-id instance-id)]]
    (doseq [message messages]
      (http/post wiremock-mappings-url {:body (json/generate-string message)}))))

(defn mock-mailjet []
  (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"  "POST"
                                                                             "urlPath" "/mailjet/send"}
                                                                 "response" {"status" 200
                                                                             "body"   "ok"}})}))

(defn text-first-email-sent-to [email]
  (->> (http/post (str wiremock-url "/__admin/requests/find")
                  {:as   :json
                   :body (json/generate-string
                           {"method"       "POST"
                            "bodyPatterns" [{"matches" (str ".*" email ".*")}]
                            "urlPath"      "/mailjet/send"})})
       :body
       :requests
       first
       :body
       (#(json/parse-string % true))
       :Text-part))

(defn report-notifications []
  (-> (http/post (str wiremock-url "/__admin/requests/find")
                 {:as   :json
                  :body (json/generate-string
                          {"urlPath" "/reports"})})
      :body
      :requests))

(defn assert-report-not-updated-in-flow []
  (is (empty? (report-notifications))))

(defn reset-wiremock []
  (http/post (str wiremock-mappings-url "/reset")))

(defn check-servers-up []
  (test-util/wait-for-server "mainnetwork" 3000)
  (test-util/wait-for-server "wiremock-proxy" 8080))

(use-fixtures :each (fn [f]
                      (check-servers-up)
                      (reset-wiremock)
                      (f)))

(deftest report-generation
  (let [survey-id (System/currentTimeMillis)]
    (mock-gae survey-id)
    (mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id)))
    (let [report-result (generate-report survey-id)]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (str/includes? (text-first-email-sent-to "dan@akvo.org") (get report-result "file")))

      (is (= 200 (:status (http/get (str flow-services-url "/report/" (get report-result "file"))))))
      (assert-report-not-updated-in-flow))))

(defn sentry-alerts-count []
  (-> (http/post (str wiremock-url "/__admin/requests/count")
                 {:as   :json
                  :body (json/generate-string
                          {"method"  "POST"
                           "urlPath" "/sentry/api/213123/store/"})})
      :body
      :count))

(deftest error-in-report-generation
  (let [survey-id (System/currentTimeMillis)
        current-errors (sentry-alerts-count)]
    (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"          "GET"
                                                                               "urlPath"         "/surveyrestapi"
                                                                               "queryParameters" {"surveyId" {"equalTo" (str survey-id)}}}
                                                                   "response" {"status" 500}})})
    (test-util/try-for "Processing for too long" 20
                       (= {"status" "ERROR", "message" "_error_generating_report"}
                          (generate-report survey-id)))
    (is (< current-errors (sentry-alerts-count)))
    (assert-report-not-updated-in-flow)))

(defn mock-flow-report-api [flow-report-id user]
  (http/post wiremock-mappings-url
             {:body (json/generate-string
                      {"request"  {"method"       "POST"
                                   "urlPath"      "/reports"
                                   "bodyPatterns" [{"matchesJsonPath" {"expression" "$.report.state" "equalTo" "IN_PROGRESS"}}
                                                   {"matchesJsonPath" {"expression" "$.report.user" "equalTo" user}}]}
                       "response" {"status"   200
                                   "jsonBody" {:report {:id flow-report-id}}}})})
  (http/post wiremock-mappings-url
             {:body (json/generate-string
                      {"request"  {"method"  "PUT"
                                   "urlPath" (str "/reports/" flow-report-id)}
                       "response" {"status"   200
                                   "jsonBody" {:id flow-report-id}}})}))

(defn final-report-state-in-flow [flow-report-id]
  (->> (http/post (str wiremock-url "/__admin/requests/find")
                  {:as   :json
                   :body (json/generate-string
                           {"method"  "PUT"
                            "urlPath" (str "/reports/" flow-report-id)})})
       :body
       :requests
       (map (comp :state :report #(json/parse-string % true) :body))))

(deftest ^:wip report-generation-flow-notified-of-progress
  (let [survey-id (System/currentTimeMillis)
        flow-report-id (str "the-flow-id-" survey-id)
        user (str survey-id "@akvo.org")
        opts {"gdpr"  "true"
              "email" user}]
    (mock-flow-report-api flow-report-id user)
    (mock-gae survey-id)
    (mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id opts)))
    (let [report-result (generate-report survey-id opts)]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (text-first-email-sent-to user))

      (is (not (str/includes? (text-first-email-sent-to user) (get report-result "file"))))

      (is (= 200 (:status (http/get (str flow-services-url "/report/" (get report-result "file"))))))
      (is (= ["FINISHED_SUCCESS"] (final-report-state-in-flow flow-report-id))))))