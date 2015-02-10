(ns akvo.flow-services.email
  (:require [postal.core :as postal]
            [taoensso.timbre :as timbre :refer (infof)]))

(def report-message
  "Hi!\n\nThe report you requested is ready for download at %s.\n\nRegards,\n\nAkvo FLOW")

(defn send-report-ready [emails file-path]
  (infof "Notifying %s about %s" emails file-path)
  (postal/send-message {:from "reports@akvoflow.org"
                        :to emails
                        ;; Not all are reports, some are forms
                        :subject "Report ready for download"
                        :body (format report-message file-path)}))
