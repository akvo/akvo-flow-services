(ns akvo.flow-services.cascade-test
  (:require [clojure.test :refer :all]
            [clojure.java.jdbc :as jdbc]
            [akvo.flow-services.cascade :as cascade])
  (:import [java.io File]))

(def comma-sep-2-levels "test/cascades/comma-sep-2-levels.csv")
(def semicolon-sep-3-levels "test/cascades/semicolon-sep-3-levels.csv")
(def tab-sep-4-levels "test/cascades/tab-sep-4-levels.csv")
(def cascade-with-empty-nodes "test/cascades/empty-nodes.csv")
(def quoted-comma-separator "test/cascades/quoted-comma-separator.csv")
;; A real three-level cascade whose first two levels repeat across rows, so the DISTINCT
;; in the per-level inserts has something to collapse. Stored with the line endings
;; `text-file-utils/clean` normalises to, which is what csv-to-db is given in production.
(def comma-sep-3-levels-repeated-parents
  "test/cascades/comma-sep-3-levels-repeated-parents.csv")

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


(deftest test-validate-csv-rejects-an-empty-file
  ;; An empty file has no row for the column and emptiness checks to reject, so they used
  ;; to answer nil and the import went on to store nothing and report success.
  (let [f (doto (File/createTempFile "cascade" ".csv") (.deleteOnExit))]
    (is (= [(format "No rows found in %s" (.getName f))]
           (cascade/validate-csv (.getAbsolutePath f) 3 \,)))))


(defn- row-count [db table]
  (:count (first (jdbc/query db [(format "SELECT count(*) AS count FROM %s" table)]))))

(deftest test-csv-to-db-derives-one-node-table-per-level
  ;; csv-to-db and the node tables it derives had no coverage, which is how an import that
  ;; stored nothing could still be reported as successful. These counts are what the
  ;; fixture must produce for the import to have anything to write.
  (let [db (cascade/csv-to-db comma-sep-3-levels-repeated-parents 3 false \,)]
    (is (= 31 (row-count db "data")) "every CSV row is staged")
    (is (= 2 (row-count db "nodes_0")) "two distinct regions")
    (is (= 2 (row-count db "nodes_1")) "one distinct province under each region")
    (is (= 31 (row-count db "nodes_2")) "every village is distinct")))

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
