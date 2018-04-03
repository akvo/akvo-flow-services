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
  (:require [akvo.flow-services.translate :refer (t>)]
            [akvo.commons.config :as config]
            [postal.core :as postal]
            [clj-http.client :as client]
            [taoensso.timbre :as timbre :refer (infof)]
            [cheshire.core :as json]))

(defn mail-jet-send [settings emails locale url]
  (let [body {"FromEmail"  (:notification-from settings)
              "Recipients" (into []
                                 (map (fn [email] {"Email" email})
                                      emails))
              "Subject"    (t> locale "_report_header")
              "Text-part"  (t> locale "_report_body" url)
              "Headers"    {"Reply-To" (:notification-reply-to settings)}}]
    (client/post (format "%s/send" (-> settings :notification :api-url))
                 {:basic-auth (-> settings :notification :credentials)
                  :headers    {"Content-Type" "application/json"}
                  :body       (json/encode body)})))

(defn postal-send [settings emails locale url]
  (postal/send-message (:notification settings)
                       {:from     (:notification-from settings)
                        :to       emails
                        :subject  (t> locale "_report_header")
                        :body     (t> locale "_report_body" url)
                        :Reply-To (:notification-reply-to settings)}))

(defn send-report-ready [emails locale url]
  (infof "Notifying %s about %s" emails url)
  (let [settings @config/settings
        send-email (if (-> settings :notification :mailjet)
                     mail-jet-send
                     postal-send)]
    (send-email settings emails locale url)))