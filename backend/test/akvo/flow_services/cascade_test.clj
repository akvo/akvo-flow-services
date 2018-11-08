(ns akvo.flow-services.cascade-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.cascade :as cascade]))

(def comma-sep-2-levels "test/cascades/comma-sep-2-levels.csv")
(def semicolon-sep-3-levels "test/cascades/semicolon-sep-3-levels.csv")
(def tab-sep-4-levels "test/cascades/tab-sep-4-levels.csv")
(def cascade-with-empty-nodes "test/cascades/empty-nodes.csv")
(def quoted-comma-separator "test/cascades/quoted-comma-separator.csv")

(deftest test-find-csv-separator
  (is (= \, (cascade/find-csv-separator comma-sep-2-levels 2)))
  (is (= \; (cascade/find-csv-separator semicolon-sep-3-levels 3)))
  (is (= \tab (cascade/find-csv-separator tab-sep-4-levels 4)))
  ;; Default to \,
  (is (= \, (cascade/find-csv-separator tab-sep-4-levels 1)))
  (is (= \, (cascade/find-csv-separator quoted-comma-separator 2))))


(deftest test-validate-csv
  (is (nil? (cascade/validate-csv comma-sep-2-levels 2 \,)))
  (is (nil? (cascade/validate-csv semicolon-sep-3-levels 3 \;)))
  (is (nil? (cascade/validate-csv tab-sep-4-levels 4 \tab)))
  (is (nil? (cascade/validate-csv quoted-comma-separator 2 \,)))
  (is (= ["Wrong number of columns 2 on line 1, Row: a,b"]
         (cascade/validate-csv comma-sep-2-levels 3 \,)))
  (is (= ["Empty cascade node on line 2. Row: d, ,f"]
         (cascade/validate-csv cascade-with-empty-nodes 3 \,)))
  (is (not (empty? (cascade/validate-csv "no-such-file" 3 \,)))))

(defn are-invalid [nodes]
  (let [[result msg] (cascade/validate-nodes-data nodes)]
    (is (= :error result))
    (is (true? (.contains msg "Found duplicate name with same parent")))))

(defn are-valid [nodes]
  (let [[result _] (cascade/validate-nodes-data nodes)]
    (is (= :ok result))))

(deftest test-validate-nodes
  (testing "same parent, different names"
    (let [the-same-parent 0]
      (are-valid [{:name "name 1" :parent the-same-parent}
                  {:name "name 2" :parent the-same-parent}])))
  (testing "same name, different parents"
    (let [the-same-name "name 1"]
      (are-valid [{:name the-same-name :parent 0}
                  {:name the-same-name :parent 1}])))
  (testing "different names and parents, but concatenation results in same"
    (are-valid [{:name "1" :parent 21}
                {:name "12" :parent 1}]))
  (testing "same name, same parent"
    (are-invalid [{:name "name 1" :parent 0}
                 {:name "name 1" :parent 0}])
    (are-invalid [{:name " name 1" :parent 0}
                 {:name "name 1 " :parent 0}])))
