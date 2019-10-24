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
            [clj-http.client :as client]
            [taoensso.timbre :as timbre :refer (infof debugf)]
            [cheshire.core :as json]
            [clojure.string :as str]))

(defn mail-jet-send [settings email locale body]
  (let [body {"FromEmail"  (:notification-from settings)
              "Recipients" [{"Email" email}]
              "Subject"    (t> locale :report-header)
              "Text-part"  body
              "Headers"    {"Reply-To" (:notification-reply-to settings)}}]
    (client/post (format "%s/send" (-> settings :notification :api-url))
                 {:basic-auth (-> settings :notification :credentials)
                  :headers    {"Content-Type" "application/json"}
                  :body       (json/encode body)})))

(defn obfuscate [email]
  (when email
    (str/replace email #"^[^@]*" "****")))

(defn send-report-ready [email locale export-type]
  (infof "Notifying %s" (obfuscate email))
  (debugf "Notifying %s " email)
  (let [settings @config/settings]
    (mail-jet-send settings email locale (t> locale export-type))))
