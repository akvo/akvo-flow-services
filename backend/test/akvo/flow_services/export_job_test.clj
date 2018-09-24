(ns akvo.flow-services.export-job-test
  (:require [clojure.test :refer [deftest is are testing]]
            [akvo.flow-services.scheduler :as scheduler]
            [akvo.flow-services.error :as e]))


(deftest export-job

  (let [flow-id "some-report-id"]
    (testing "flow notifications"
      (testing "create request"
        (is (= (scheduler/create-report-in-flow {"opts"       {"email"    "user@akvo.org"
                                                               "reportId" flow-id
                                                               "from"     "2000-01-01"
                                                               "to"       "9999-01-01"}
                                                 "baseURL"    "http://foobar"
                                                 "surveyId"   "000000000000"
                                                 "exportType" "GEOSHAPE"})
               {:method      :put
                :url         (str "http://foobar/rest/reports/" flow-id)
                :form-params {:report {:state      "IN_PROGRESS"
                                       :keyId      flow-id
                                       :startDate  "2000-01-01"
                                       :endDate    "9999-01-01"
                                       :formId     "000000000000"
                                       :reportType "GEOSHAPE"}}})))

      (testing "finish request"
        (are [report-result expected-body]
          (let [an-email (str (rand-int 30000) "-user@akvo.org")]
            (is (= (scheduler/finish-report-in-flow {"opts"       {"email"        an-email
                                                                   "reportId"     flow-id
                                                                   "flowServices" "http://some-flow-url:23423"
                                                                   "from"         "2000-01-01"
                                                                   "to"           "9999-01-01"}
                                                     "baseURL"    "http://foobar"
                                                     "surveyId"   "000000000000"
                                                     "exportType" "COOL"}
                                                    report-result)
                   {:method      :put
                    :url         (str "http://foobar/rest/reports/" flow-id)
                    :form-params {:report (merge {:keyId      flow-id
                                                  :formId     "000000000000"
                                                  :startDate  "2000-01-01"
                                                  :endDate    "9999-01-01"
                                                  :reportType "COOL"}
                                                 expected-body)}})))

          "some awesome path" {:state    "FINISHED_SUCCESS"
                               :filename "http://some-flow-url:23423/report/some awesome path"}

          (e/error {:cause (RuntimeException. "Something very bad")}) {:state   "FINISHED_ERROR"
                                                                       :message "Something very bad"}))))
  )