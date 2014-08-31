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

(ns akvo.flow-services.gae
  (:import java.util.Date
    [com.google.appengine.tools.remoteapi RemoteApiInstaller RemoteApiOptions]
    [com.google.appengine.api.datastore DatastoreServiceFactory Entity Query
     Query$FilterOperator Query$CompositeFilterOperator Query$FilterPredicate
     PreparedQuery FetchOptions FetchOptions$Builder KeyFactory Key]))

(defn get-options 
  "Returns a RemoteApiOptions object"
  [server usr pwd]
  (doto
    (RemoteApiOptions.)
    (.server server 443)
    (.credentials usr pwd)))

(defn get-installer
  "Returns a RemoteApiInstaller object"
  [opts]
  (doto
    (RemoteApiInstaller.)
    (.install opts)))

(defn get-fetch-options
  "Returns the fetch options for a PreparedQuery"
  ([]
    (FetchOptions$Builder/withDefaults))
  ([size]
    (FetchOptions$Builder/withChunkSize size)))


(defn get-ds
  "Returns an instance of a DatastoreService"
  []
  (DatastoreServiceFactory/getDatastoreService))

(defn get-filter
  "Helper function that returns a FilterPredicate based on a property"
  ([property value]
    (Query$FilterPredicate. property Query$FilterOperator/EQUAL value))
  ([property value operator]
    (cond
      (= operator :eq) (Query$FilterPredicate. property Query$FilterOperator/EQUAL value)
      (= operator :lt) (Query$FilterPredicate. property Query$FilterOperator/LESS_THAN value)
      (= operator :lte) (Query$FilterPredicate. property Query$FilterOperator/LESS_THAN_OR_EQUAL value)
      (= operator :gt) (Query$FilterPredicate. property Query$FilterOperator/GREATER_THAN value)
      (= operator :gte) (Query$FilterPredicate. property Query$FilterOperator/GREATER_THAN_OR_EQUAL value)
      (= operator :ne) (Query$FilterPredicate. property Query$FilterOperator/NOT_EQUAL value)
      (= operator :in) (Query$FilterPredicate. property Query$FilterOperator/IN value))))

(defn get-and-filter
  [& filters]
  (Query$CompositeFilterOperator/and filters))

(defn get-or-filter
  [& filters]
  (Query$CompositeFilterOperator/or filters))

(defn get-key
  [kind id]
  (KeyFactory/createKey kind id))

(defn put!
  "Creates a new Entity using Remote API"
  [server usr pwd entity-name props]
  (let [opts (get-options server usr pwd)
        installer (get-installer opts)
        ds (get-ds)
        entity (Entity. entity-name)
        ts (Date.)]
    (doseq [k (keys props)]
      (.setProperty entity k (props k)))
    (.setProperty entity "createdDateTime" ts)
    (.setProperty entity "lastUpdateDateTime" ts)
    (.put ds entity)))