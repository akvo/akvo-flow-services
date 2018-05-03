(ns akvo.flow-services.export-job-test
  (:require [clojure.test :refer [deftest is are testing]]
            [akvo.flow-services.scheduler :as scheduler]
            [akvo.flow-services.error :as e])
  (:import (java.io IOException)))


(deftest gdpr?
  (is (scheduler/gdpr-flow? {"opts" {"gdpr" "true"}}))
  (is (not (scheduler/gdpr-flow? {"opts" {}})))
  (is (not (scheduler/gdpr-flow? {"opts" {"gdpr" "false"}})))

  (let [flow-id "id-returned-by-flow"]
    (testing "flow notifications"
      (testing "create request"
        (is (= (scheduler/create-report-in-flow {"opts"       {"email" "user@akvo.org"}
                                                 "baseURL"    "http://foobar"
                                                 "exportType" "GEOSHAPE"})
               {:method      :post
                :url         "http://foobar/rest/reports"
                :form-params {:report {:state      "IN_PROGRESS"
                                       :user       "user@akvo.org"
                                       :reportType "GEOSHAPE"}}})))

      (testing "handle create response"
        (is (= (scheduler/handle-create-report-in-flow {:status 200
                                                        :body   {:report {:keyId flow-id}}})
               flow-id))

        (is (= (scheduler/handle-create-report-in-flow {:status 200
                                                        :body   nil})
               (e/error {:message "Flow did not return an id for the report"})))

        (is (= (scheduler/handle-create-report-in-flow {:status 500})
               (e/error {:message "Flow returns error on report creation. HTTP code: 500"})))

        (let [an-exception (IOException. "some error")
              error (::e/error (scheduler/handle-create-report-in-flow (e/error {:cause an-exception})))]
          (is (= (:cause error) an-exception))))

      (testing "finish request"
        (are [report-result expected-body]
          (let [an-email (str (rand-int 30000) "-user@akvo.org")]
            (= (scheduler/finish-report-in-flow {"opts"       {"email" an-email}
                                                 "baseURL"    "http://foobar"
                                                 "exportType" "COOL"}
                                                flow-id
                                                report-result)
               {:method      :put
                :url         "http://foobar/rest/reports/id-returned-by-flow"
                :form-params {:report (merge {:user       an-email
                                              :keyId      "id-returned-by-flow"
                                              :reportType "COOL"}
                                             expected-body)}}))

          "some awesome path" {:state    "FINISHED_SUCCESS"
                               :filename "some awesome path"}

          (e/error {:cause (RuntimeException. "Something very bad")}) {:state   "FINISHED_ERROR"
                                                                       :message "Something very bad"}))))
  )