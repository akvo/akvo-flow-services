(ns akvo.flow-services.cascade-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.cascade :as cascade]))

(def comma-sep-2-levels "resources/test/cascades/comma-sep-2-levels.csv")
(def semicolon-sep-3-levels "resources/test/cascades/semicolon-sep-3-levels.csv")
(def tab-sep-4-levels "resources/test/cascades/tab-sep-4-levels.csv")
(def cascade-with-empty-nodes "resources/test/cascades/empty-nodes.csv")

(deftest test-find-csv-separator
  (is (= \, (cascade/find-csv-separator comma-sep-2-levels 2)))
  (is (= \; (cascade/find-csv-separator semicolon-sep-3-levels 3)))
  (is (= \tab (cascade/find-csv-separator tab-sep-4-levels 4)))
  ;; Default to \,
  (is (= \, (cascade/find-csv-separator tab-sep-4-levels 1))))


(deftest test-validate-csv
  (is (nil? (cascade/validate-csv comma-sep-2-levels 2 \,)))
  (is (nil? (cascade/validate-csv semicolon-sep-3-levels 3 \;)))
  (is (nil? (cascade/validate-csv tab-sep-4-levels 4 \tab)))
  (is (= ["Wrong number of columns 2 on line 1, Row: a,b"]
         (cascade/validate-csv comma-sep-2-levels 3 \,)))
  (is (= ["Empty cascade node on line 2. Row: d, ,f"]
         (cascade/validate-csv cascade-with-empty-nodes 3 \,)))
  (is (not (empty? (cascade/validate-csv "no-such-file" 3 \,)))))
