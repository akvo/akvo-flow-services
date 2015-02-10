(ns akvo.flow-services.email
  (:require [postal.core :as postal]))

(def report-message
  "Hi!\n\nThe report you requested is ready for download at %s.\n\nRegards,\n\nAkvo FLOW")

(defn send-report-ready [emails file-path]
  (postal/send-message {:from "reports@akvoflow.org"
                        :to emails
                        ;; Not all are reports, some are forms
                        :subject "Report ready for download"
                        :message (format report-message file-path)}))
