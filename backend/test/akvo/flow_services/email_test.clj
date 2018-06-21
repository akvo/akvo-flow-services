(ns akvo.flow-services.email-test
  (:require [clojure.test :refer :all]
            [akvo.flow-services.email :as email]))

(deftest hide-emails
  (are [email expected] (= (email/obfuscate email) expected)
    "any.email@akvo.org" "****@akvo.org"
    "x@foo.org" "****@foo.org"
    "no at symbol" "****"
    nil nil))
