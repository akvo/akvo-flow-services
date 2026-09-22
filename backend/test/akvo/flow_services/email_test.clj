(ns akvo.flow-services.email-test
  "Covers what this namespace does that postal does not: assembling the message
  and the server map, and turning a rejected send into an exception."
  (:require [clojure.test :refer :all]
            [postal.core :as postal]
            [akvo.flow-services.email :as email]))

(def settings
  {:notification          {:host    "smtp.example.org"
                           :port    587
                           :user    "noreply@akvo.org"
                           :pass    "secret"
                           :tls     true
                           :ssl     false
                           :timeout 10000}
   :notification-from     "noreply@akvo.org"
   :notification-reply-to "reports@akvoflow.org"})

(deftest hide-emails
  (are [email expected] (= (email/obfuscate email) expected)
    "any.email@akvo.org" "****@akvo.org"
    "x@foo.org" "****@foo.org"
    "no at symbol" "****"
    nil nil))

(deftest send-builds-the-message
  (let [sent (atom nil)]
    (with-redefs [postal/send-message (fn [server message]
                                        (reset! sent {:server server :message message})
                                        {:code 0 :error :SUCCESS :message "message sent"})]
      (email/smtp-send settings "user@akvo.org" "en" "the body"))
    (testing "sender and reply-to come from config, recipient from the call"
      (is (= "noreply@akvo.org" (-> @sent :message :from)))
      (is (= "reports@akvoflow.org" (-> @sent :message :reply-to)))
      (is (= "user@akvo.org" (-> @sent :message :to))))
    (testing "the subject is translated and the body passes through untouched"
      (is (= "Your report is ready to download" (-> @sent :message :subject)))
      (is (= "the body" (-> @sent :message :body))))
    (testing "tls and ssl reach postal under the names it expects"
      (is (true? (-> @sent :server :tls)))
      (is (false? (-> @sent :server :ssl))))
    (testing "timeouts are strings, or jakarta.mail reads them back as nil"
      (is (= "10000" (-> @sent :server :timeout)))
      (is (= "10000" (-> @sent :server :connectiontimeout))))))

(deftest blank-credentials-become-nil
  ;; An empty string is truthy, so leaving it in place would make postal attempt
  ;; an AUTH handshake against a relay that never asked for one.
  (let [sent (atom nil)]
    (with-redefs [postal/send-message (fn [server _]
                                        (reset! sent server)
                                        {:code 0 :error :SUCCESS})]
      (email/smtp-send (update settings :notification assoc :user "" :pass "")
                       "user@akvo.org" "en" "the body"))
    (is (nil? (:user @sent)))
    (is (nil? (:pass @sent)))))

(deftest send-raises-when-the-relay-rejects-the-message
  ;; Postal signals a rejection by returning a value rather than throwing, so
  ;; without this a dropped message is indistinguishable from a delivered one.
  (with-redefs [postal/send-message (fn [_ _] {:code 99 :error :FAILURE :message "rejected"})]
    (is (thrown? clojure.lang.ExceptionInfo
                 (email/smtp-send settings "user@akvo.org" "en" "the body")))))
