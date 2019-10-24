;  Copyright (C) 2015,2019 Stichting Akvo (Akvo Foundation)
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

(ns akvo.flow-services.translate
  (:require [clojure.java.io :as io]
            [aero.core :as aero]))

(def translation-map (aero/read-config (io/resource "ui-strings.edn")))

(defn t> [locale key & args]
  (let [s (or (get-in translation-map [key (keyword locale)])
              (get-in translation-map [key :en]))]
    (assert s (str "No translation string for key " key))
    (apply format s args)))
