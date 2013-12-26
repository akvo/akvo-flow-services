(ns akvo.flow-services.stats
  (:import [com.google.appengine.tools.remoteapi RemoteApiInstaller RemoteApiOptions]
           [com.google.appengine.api.datastore DatastoreService DatastoreServiceFactory Entity Query
            Query$FilterOperator Query$FilterPredicate PreparedQuery FetchOptions FetchOptions$Builder]))


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

(defn get-defaults
  "Returns the defaults options for a PreparedQuery"
  []
  (FetchOptions$Builder/withDefaults))

(defn get-ds
  "Returns an instance of a DatastoreService"
  []
  (DatastoreServiceFactory/getDatastoreService))

(defn get-filter
  "Helper function that returns a FilterPredicate based on a property"
  [property value]
  (Query$FilterPredicate. property Query$FilterOperator/EQUAL value))

(defn get-stats
  "Returns a list of stats for the given instance"
  [server usr pwd]
  (let [opts (get-options server usr pwd)
        installer (get-installer opts)
        ds (get-ds)
        qt (Query. "__Stat_Total__")
        total (.asSingleEntity (.prepare ds qt))
        ts (.getProperty total "timestamp")
        qk (.setFilter (Query. "__Stat_Kind__") (get-filter "timestamp" ts))
        stats (.asList (.prepare ds qk) (get-defaults))]
    (.uninstall installer)
    stats))
