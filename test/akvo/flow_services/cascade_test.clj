(ns akvo.flow-services.cascade-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.cascade :as cascade]))

(def comma-sep-2-levels "resources/test/cascades/comma-sep-2-levels.csv")
(def semicolon-sep-3-levels "resources/test/cascades/semicolon-sep-3-levels.csv")
(def tab-sep-4-levels "resources/test/cascades/tab-sep-4-levels.csv")

(deftest test-find-csv-separator
  (is (= \, (cascade/find-csv-separator comma-sep-2-levels 2)))
  (is (= \; (cascade/find-csv-separator semicolon-sep-3-levels 3)))
  (is (= \tab (cascade/find-csv-separator tab-sep-4-levels 4)))
  ;; Default to \,
  (is (= \, (cascade/find-csv-separator tab-sep-4-levels 1))))
