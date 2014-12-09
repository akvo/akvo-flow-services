;  Copyright (C) 2014 Stichting Akvo (Akvo Foundation)
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

(ns akvo.flow-services.log
  (:require [taoensso.timbre :as timbre])
  (:import [org.apache.log4j Logger Level]))

(defn set-debug
  "Sets timbre and log4j logging level to DEBUG"
  []
  (timbre/set-level! :debug)
  (.setLevel (Logger/getLogger "com.gallatinsystems") Level/DEBUG)
  (.setLevel (Logger/getLogger "org.waterforpeople") Level/DEBUG))

(defn set-info
  "Sets timbre and log4j logging level to INFO"
  []
  (timbre/set-level! :info)
  (.setLevel (Logger/getLogger "com.gallatinsystems") Level/INFO)
  (.setLevel (Logger/getLogger "org.waterforpeople") Level/INFO))
