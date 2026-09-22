;  Copyright (C) 2015 Stichting Akvo (Akvo Foundation)
;
;  This file is part of Akvo FLOW.
;
;  Akvo FLOW is free software: you can redistribute it and modify it under the terms of
;  the GNU Affero General Public License (AGPL) as published by the Free Software Foundation,
;  either version 3 of the License or any later version.
;
;  Akvo FLOW is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
;  without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
;  See the GNU Affero General Public License included below for more details.
;
;  The full license text can also be seen at <http://www.gnu.org/licenses/agpl.html>.

(ns akvo.flow-services.email
  "Notifies a user that the report they asked for is ready.

  Delivery goes through an SMTP relay. See akvo/akvo-flow-services#326 for why
  this replaced the Mailjet v3 HTTP client."
  (:require [akvo.flow-services.translate :refer (t>)]
            [akvo.commons.config :as config]
            [postal.core :as postal]
            [taoensso.timbre :as timbre :refer (infof debugf)]
            [clojure.string :as str]))

;; The SMTP server map is built here rather than in config, because two of its
;; values cannot be written as EDN. Postal consumes a few of these keys itself
;; and hands every other one to jakarta.mail as a `mail.smtp.*` property; `:tls`
;; is rewritten to `starttls.enable`, and `:ssl` selects the `smtps` protocol
;; instead of becoming a property at all.

(defn smtp-send [settings email locale body]
  (let [{:keys [host port user pass tls ssl timeout]} (:notification settings)
        server {:host host
                :port port
                ;; Blank credentials have to become nil. Postal derives
                ;; `mail.smtp.auth` from whether `:user` is truthy, so "" makes
                ;; it attempt an AUTH handshake against a relay that never asked
                ;; for one, and it asserts that user and pass are either both
                ;; present or both absent.
                :user (not-empty user)
                :pass (not-empty pass)
                :tls  tls
                :ssl  ssl
                ;; Strings, because these reach jakarta.mail as properties and it
                ;; reads those back with `Properties/getProperty`, which returns
                ;; nil for a value stored as a number -- leaving the socket with
                ;; no timeout at all. The Mailjet path had none either, which is
                ;; how a hung provider could hold a Quartz worker thread.
                :connectiontimeout (str timeout)
                :timeout           (str timeout)}
        {:keys [error] :as result}
        (postal/send-message server
                             {:from     (:notification-from settings)
                              :to       email
                              :reply-to (:notification-reply-to settings)
                              :subject  (t> locale :report-header)
                              :body     body})]
    ;; Postal reports a rejected message by *returning* `{:error :FAILURE}`
    ;; rather than throwing -- it only throws when the connection or handshake
    ;; itself fails. Left unchecked that makes a dropped message and a delivered
    ;; one look identical to the caller, which is the exact silent failure this
    ;; namespace was rewritten to avoid. Raising also matches the Mailjet
    ;; client, which threw on a non-2xx response, so the error tracker keeps
    ;; seeing send failures the way it always has.
    (when-not (= :SUCCESS error)
      (throw (ex-info "Could not send report notification" {:result result})))))

(defn obfuscate [email]
  (when email
    (str/replace email #"^[^@]*" "****")))

(defn send-report-ready [email locale export-type]
  (infof "Notifying %s" (obfuscate email))
  (debugf "Notifying %s " email)
  (let [settings @config/settings]
    (smtp-send settings email locale (t> locale export-type))))
