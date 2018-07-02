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

(defn generate-report [survey-id & [opts]]
  (some->> (http/get (str test-util/flow-services-url "/generate")
                     {:query-params {:callback "somejson"
                                     :criteria (json/generate-string
                                                 {"baseURL"    test-util/wiremock-url
                                                  "exportType" "DATA_CLEANING"
                                                  "surveyId"   (str survey-id)
                                                  "id"         "asdf"
                                                  "opts"       (merge {"email"          "dan@akvo.org"
                                                                       "lastCollection" "false"
                                                                       "uploadUrl"      "https://akvoflowsandbox.s3.amazonaws.com"
                                                                       "exportMode"     "DATA_CLEANING"
                                                                       "from"           "2013-03-06"
                                                                       "to"             "2018-03-09"
                                                                       "flowServices"   test-util/flow-services-url
                                                                       "appId"          "akvoflowsandbox"}
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
  (survey-rest-api "listSurveyQuestions" survey-id [{"keyId"                   question-id
                                                     "text"                    "Financier or donor of rehabilitation"
                                                     "order"                   1
                                                     "surveyId"                survey-id
                                                     "questionGroupId"         question-group-id
                                                     "allowDecimal"            false
                                                     "allowSign"               false
                                                     "dependentFlag"           true
                                                     "allowMultipleFlag"       false
                                                     "allowOtherFlag"          true
                                                     "mandatoryFlag"           true
                                                     "tip"                     ""
                                                     "collapseable"            false
                                                     "immutable"               false
                                                     "allowExternalSources"    false
                                                     "geoLocked"               false
                                                     "localeNameFlag"          false
                                                     "requireDoubleEntry"      false
                                                     "localeLocationFlag"      false
                                                     "variableName"            "rehabDonor"
                                                     "allowPoints"             false
                                                     "allowLine"               false
                                                     "allowPolygon"            false
                                                     "caddisflyResourceUuid"   "null"
                                                     "type"                    "OPTION"
                                                     "path"                    ""}]))

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

(defn gae-survey-options [survey-id question-id]
  (survey-rest-api "listSurveyQuestionOptions" survey-id [{"text" "Yes"
                                                           "order" 1
                                                           "questionId" question-id
                                                           "keyId" 3586128}
                                                          {"text" "No"
                                                           "order" 2
                                                           "code" nil
                                                           "translationMap" nil
                                                           "questionId" question-id
                                                           "keyId" 10136126}]))

(defn mock-gae [survey-id]
  (let [question-group-id 148442015
        question-id 144672013
        instance-id 144672047
        messages [(gae-survey-group survey-id)
                  (gae-list-survey-questions survey-id question-group-id question-id)
                  (gae-survey-options survey-id question-id)
                  (gae-list-groups survey-id question-group-id)

                  (gae-list-instances survey-id instance-id)
                  (instance-responses instance-id question-id)
                  (test-util/instance-data survey-id instance-id)]]
    (test-util/setup-wiremock messages)))

(defn text-first-email-sent-to [email]
  (->> (http/post (str test-util/wiremock-url "/__admin/requests/find")
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
  (-> (http/post (str test-util/wiremock-url "/__admin/requests/find")
                 {:as   :json
                  :body (json/generate-string
                          {"urlPath" "/rest/reports"})})
      :body
      :requests))

(defn assert-report-not-updated-in-flow []
  (is (empty? (report-notifications))))

(use-fixtures :each test-util/fixture)

(deftest report-generation
  (let [survey-id (System/currentTimeMillis)]
    (mock-gae survey-id)
    (test-util/mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id)))
    (let [report-result (generate-report survey-id)]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (str/includes? (text-first-email-sent-to "dan@akvo.org") (get report-result "file")))

      (is (= 200 (:status (test-util/get-report report-result))))
      (assert-report-not-updated-in-flow))))

(defn sentry-alerts-count []
  (-> (http/post (str test-util/wiremock-url "/__admin/requests/count")
                 {:as   :json
                  :body (json/generate-string
                          {"method"  "POST"
                           "urlPath" "/sentry/api/213123/store/"})})
      :body
      :count))

(deftest error-in-report-generation
  (let [survey-id (System/currentTimeMillis)
        current-errors (sentry-alerts-count)]
    (http/post test-util/wiremock-mappings-url {:body (json/generate-string {"request"  {"method"          "GET"
                                                                                         "urlPath"         "/surveyrestapi"
                                                                                         "queryParameters" {"surveyId" {"equalTo" (str survey-id)}}}
                                                                             "response" {"status" 500}})})
    (test-util/try-for "Processing for too long" 20
                       (= {"status" "ERROR", "message" "_error_generating_report"}
                          (generate-report survey-id)))
    (is (< current-errors (sentry-alerts-count)))
    (assert-report-not-updated-in-flow)))

(defn mock-flow-report-api [flow-report-id]
  (http/post test-util/wiremock-mappings-url
             {:body (json/generate-string
                      {"request"  {"method"  "PUT"
                                   "urlPath" (str "/rest/reports/" flow-report-id)}
                       "response" {"status"   200
                                   "jsonBody" {:report {:keyId flow-report-id}}}})}))

(defn final-report-state-in-flow [flow-report-id]
  (->> (http/post (str test-util/wiremock-url "/__admin/requests/find")
                  {:as   :json
                   :body (json/generate-string
                           {"method"  "PUT"
                            "urlPath" (str "/rest/reports/" flow-report-id)})})
       :body
       :requests
       (map (comp :state :report #(json/parse-string % true) :body))))

(deftest report-generation-flow-notified-of-progress
  (let [survey-id (System/currentTimeMillis)
        flow-report-id (str "the-flow-id-" survey-id)
        user (str survey-id "@akvo.org")
        opts {"reportId" flow-report-id
              "email"    user}]
    (mock-flow-report-api flow-report-id)
    (mock-gae survey-id)
    (test-util/mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id opts)))
    (let [report-result (generate-report survey-id opts)]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (text-first-email-sent-to user))

      (is (not (str/includes? (text-first-email-sent-to user) (get report-result "file"))))

      (is (= 200 (:status (test-util/get-report report-result))))
      (is (= ["IN_PROGRESS" "FINISHED_SUCCESS"] (final-report-state-in-flow flow-report-id))))))