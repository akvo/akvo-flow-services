(ns akvo.flow-services.classpath-test
  (:require [clojure.test :refer :all]))

(deftest test-gson-is-on-the-classpath
  ;; appengine-remote-api needs this unshaded class and nothing else supplies it.
  ;; Without the explicit dependency: ClassNotFoundException here, NoClassDefFoundError
  ;; on the first remote-API datastore call in production.
  (is (Class/forName "com.google.gson.stream.JsonWriter")))
