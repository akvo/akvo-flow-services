(ns akvo.flow-services.stats-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.stats :as stats]
            [akvo.flow-services.error :as e]))

(def error (e/error "some error"))
(def ok "")

(deftest too-many-errors
  (is (= (stats/too-many-errors? [error error])
         {:level :error
          :message "Too many errors collecting stats"
          :data [error error]}))
  (is (= (stats/too-many-errors? [error ok ok ok])
         {:level   :error
          :message "Too many errors collecting stats"
          :data    [error]}))
  (is (= (stats/too-many-errors? [error ok ok ok ok])
         {:level   :info
          :message "Some errors collecting stats"
          :data    [error]}))
  (is (= (stats/too-many-errors? [ok])
         {:level   :info
          :message "No errors collecting stats"
          :data ""}))
  (is (= (stats/too-many-errors? [])
         {:level   :info
          :message "No errors collecting stats"
          :data ""})))
