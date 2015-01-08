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

(ns akvo.flow-services.text-file-utils
  (:require [clojure.java.io :as io]
            [clojure.string :as s])
  (:import [org.apache.tika Tika]
           [java.io FileWriter]))

(defn clean
  "Clean from-file by ensuring UTF-8 encoding and normalized line endings. Write the result to to-file"
  [from-file to-file]
  (with-open [input-stream (io/input-stream (io/file from-file))
              reader (io/reader (.parse (Tika.) input-stream))
              file-writer (FileWriter. to-file)]
    ;; line-seq handles \r, \r\n and \n
    (doseq [line (line-seq reader)]
      (.write file-writer line)
      (.write file-writer "\n"))))
