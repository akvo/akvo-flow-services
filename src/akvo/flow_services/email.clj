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
            [postal.core :as postal]
            [taoensso.timbre :as timbre :refer (infof)]))

(defn send-report-ready [emails locale url]
  (infof "Notifying %s about %s" emails url)
  (postal/send-message {:from "reports@akvoflow.org"
                        :to emails
                        ;; Not all are reports, some are forms
                        :subject (t> locale "_report_header")
                        :body (t> locale "_report_body" url)}))
