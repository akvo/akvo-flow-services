(ns akvo.flow-services.end-to-end-geoshape-test
  {:integration true}
  (:require [clojure.test :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [akvo.flow-services.test-util :as test-util]
            [akvo.flow-services.util :as util]
            [akvo.commons.gae :as gae])
  (:import java.util.Base64))

(defn encode [to-encode]
  (.encodeToString (Base64/getEncoder) (.getBytes to-encode)))

(def wiremock-url "http://wiremock-proxy:8080")
(def wiremock-mappings-url (str wiremock-url "/__admin/mappings"))
(def flow-services-url "http://mainnetwork:3000")

(defn generate-report [survey-id question-id]
  (some->> (http/get (str flow-services-url "/generate")
                     {:query-params {:callback "somejson"
                                     :criteria (json/generate-string
                                                 {"baseURL"    wiremock-url
                                                  "exportType" "GEOSHAPE"
                                                  "surveyId"   (str survey-id)
                                                  "id"         "asdfxxxxx"
                                                  "opts"       {"email"          "dan@akvo.org"
                                                                "lastCollection" "false"
                                                                "imgPrefix"      "https://akvoflowsandbox.s3.amazonaws.com/images/",
                                                                "uploadUrl"      "https://akvoflowsandbox.s3.amazonaws.com/",
                                                                "exportMode"     "GEOSHAPE"
                                                                "questionId"     question-id
                                                                "flowServices"   flow-services-url
                                                                "appId"          "flowservices-dev-config"}})}})
           :body
           (re-seq #"somejson\((.*)\);")
           first
           second
           json/parse-string))

(defn instance-data [survey-id instance-id]
  {"request"  {"method"          "GET"
               "urlPath"         "/instancedata"
               "queryParameters" {"action"           {"equalTo" "getInstanceData"}
                                  "surveyInstanceId" {"equalTo" (str instance-id)}}}
   "response" {"status"   200
               "jsonBody" {"surveyInstanceData"   {"surveyedLocaleDisplayName" ""
                                                   "surveyId"                  survey-id
                                                   "surveyedLocaleId"          147442028
                                                   "userID"                    1
                                                   "collectionDate"            1418598306000
                                                   "submitterName"             "BASH & JAMES"
                                                   "deviceIdentifier"          "IMPORTER"
                                                   "surveyalTime"              0
                                                   "surveyedLocaleIdentifier"  "a6ey-5r1h-6a0y"
                                                   "keyId"                     instance-id}
                           "latestApprovalStatus" ""
                           "resultCount"          0
                           "offset"               0}}})

(defn mock-gae [survey-id]
  (let [instance-id 144672047
        messages [(instance-data survey-id instance-id)]]
    (doseq [message messages]
      (http/post wiremock-mappings-url {:body (json/generate-string message)}))))

(defn mock-mailjet []
  (http/post wiremock-mappings-url {:body (json/generate-string {"request"  {"method"  "POST"
                                                                             "urlPath" "/mailjet/send"}
                                                                 "response" {"status" 200
                                                                             "body"   "ok"}})}))

(defn email-sent-count [body]
  (-> (http/post (str wiremock-url "/__admin/requests/count")
                 {:as   :json
                  :body (json/generate-string
                          {"method"       "POST"
                           "bodyPatterns" [{"matches" (str ".*" body ".*")}]
                           "urlPath"      "/mailjet/send"})})
      :body
      :count))

(defn reset-wiremock []
  (http/post (str wiremock-mappings-url "/reset")))

(defn check-servers-up []
  (test-util/wait-for-server "mainnetwork" 3000)
  (test-util/wait-for-server "wiremock-proxy" 8080))

(use-fixtures :once (fn [f]
                      (check-servers-up)
                      (reset-wiremock)
                      (f)))

(deftest report-generation
  (let [survey-id (System/currentTimeMillis)
        question-ids (gae/with-datastore [ds {:hostname "localhost"
                                              :port     8888}]
                       {:geo-question (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "GeoQuestion"}))
                        :another-question (.getId (gae/put! ds "Question" {"surveyId" survey-id "text" "AnotherQuestion"}))})]
    (gae/with-datastore [ds (util/datastore-spec "flowservices-dev-config")]
      (gae/put! ds "QuestionAnswerStore"
                {"surveyId"         survey-id
                 "value"            (json/generate-string {:features [{:type       "Feature",
                                                                       :properties {:length "598909.00", :pointCount "3", :area "13872516944.93"},
                                                                       :geometry   {:type        "Polygon",
                                                                                    :coordinates [[[43.657240234315395 43.805380480517236]
                                                                                                   [44.38478909432889 42.843155681966564]
                                                                                                   [46.41390200704336 44.473117973161685]
                                                                                                   [43.657240234315395 43.805380480517236]]]}}],
                                                           :type     "FeatureCollection"})
                 "iteration"        0
                 "surveyInstanceId" 1232133
                 "questionID"       (str (:geo-question question-ids))})
      (gae/put! ds "QuestionAnswerStore"
                {"surveyId"         survey-id
                 "value"            "the other question response"
                 "iteration"        0
                 "surveyInstanceId" 1232133
                 "questionID"       (str (:another-question question-ids))}))
    (mock-gae survey-id)
    (mock-mailjet)
    (test-util/try-for "Processing for too long" 20
                       (not= {"status" "OK", "message" "PROCESSING"}
                             (generate-report survey-id (:geo-question question-ids))))
    (let [report-result (generate-report survey-id (:geo-question question-ids))]
      (is (= "OK" (get report-result "status")))

      (test-util/try-for "email not sent" 5
                         (= 1 (email-sent-count (get report-result "file"))))

      (is (= 200 (:status (http/get (str flow-services-url "/report/" (get report-result "file"))))))
      (is (= {:type       "FeatureCollection",
              :properties {:formId survey-id, :questionId (:geo-question question-ids), :questionText "GeoQuestion"},
              :features   [{:type       "Feature",
                            :properties {:length "598909.00", :pointCount "3", :area "13872516944.93", :AnotherQuestion "the other question response"},
                            :geometry   {:type        "Polygon",
                                         :coordinates [[[43.657240234315395 43.805380480517236]
                                                        [44.38478909432889 42.843155681966564]
                                                        [46.41390200704336 44.473117973161685]
                                                        [43.657240234315395 43.805380480517236]]]}}]}
             (json/parse-string (:body (http/get (str flow-services-url "/report/" (get report-result "file")))) true)))
      )))


(comment

  (http/get (str flow-services-url "/report/" "akvoflowsandbox/379db636-bdaf-46c5-805c-a0faf4cb23f3/GEOSHAPE-58863002-57103002.json"))


  (cheshire.core/parse-string (slurp (akvo.flow-services.geoshape-export/export "akvoflowsandbox" "58863002" 57103002)) true)

  (akvo.flow-services.geoshape-export/afffdf "flowservices-dev-config" "98765432")

  (let [survey-id 987654322
        question-ids (gae/with-datastore [ds {:hostname "localhost"
                                              :port     8888}]
                       {:area (.getId (gae/put! ds "Question" {"surveyId" survey-id
                                                               "text"     "area"}))
                        :name (.getId (gae/put! ds "Question" {"surveyId" survey-id
                                                               "text"     "Name"}))})]
    (println question-ids)
    (gae/with-datastore [ds (util/datastore-spec "flowservices-dev-config")]
      (gae/put! ds "QuestionAnswerStore"
                {"surveyId"         survey-id
                 "value"            (json/generate-string {:features [{:type       "Feature",
                                                                       :properties {:length "598909.00", :pointCount "3", :area "13872516944.93"},
                                                                       :geometry   {:type        "Polygon",
                                                                                    :coordinates [[[43.657240234315395 43.805380480517236]
                                                                                                   [44.38478909432889 42.843155681966564]
                                                                                                   [46.41390200704336 44.473117973161685]
                                                                                                   [43.657240234315395 43.805380480517236]]]}}],
                                                           :type     "FeatureCollection"})
                 "iteration"        0
                 "surveyInstanceId" 1232133
                 "questionID"       (str (:area question-ids))})
      (gae/put! ds "QuestionAnswerStore"
                {"surveyId"         survey-id
                 "value"            "the name"
                 "iteration"        0
                 "surveyInstanceId" 1232133
                 "questionID"       (str (:name question-ids))})))

  (cheshire.core/parse-string (slurp (akvo.flow-services.geoshape-export/export "flowservices-dev-config" "987654322" 6333736731803648)) true)

  (first *1)
  (.getId *1)

  (gae/with-datastore [ds (util/datastore-spec "flowservices-dev-config")]
    (gae/put! ds "QuestionAnswerStore"
              {"surveyId"         98765432
               "value"            22
               "iteration"        0
               "surveyInstanceId" 1232133
               "questionID"       "12321332"})
    )



  (def questions {50653003 "line",
                  50673002 "Name",
                  50683002 "number",
                  53363002 "option",
                  57093002 "location",
                  57103002 "area",
                  58873002 "point"})

  (def responses
    {57953002 {50653003 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"552675.25\",\"pointCount\":\"4\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[43.23931384831666,43.78561914089676],[44.20807980000973,42.921363044661035],[44.08121768385172,45.14443180309269],[45.61509657651187,43.935390171696014]]}}],\"type\":\"FeatureCollection\"}",
               50673002 "vudhxhx",
               50683002 "3",
               53363002 "[{\"text\":\"hi\"}]",
               57093002 "44.3853|44.9928|557|1kn16fhu0",
               57103002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"598909.00\",\"pointCount\":\"3\",\"area\":\"13872516944.93\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[43.657240234315395,43.805380480517236],[44.38478909432889,42.843155681966564],[46.41390200704336,44.473117973161685],[43.657240234315395,43.805380480517236]]]}}],\"type\":\"FeatureCollection\"}",
               58873002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"pointCount\":\"1\"},\"geometry\":{\"type\":\"MultiPoint\",\"coordinates\":[[44.498739056289196,39.01059905333942]]}}],\"type\":\"FeatureCollection\"}"},
     55203002 {50653003 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"1036760.50\",\"pointCount\":\"5\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[42.07189612090587,43.58658340603489],[42.62982007116079,45.83737041558021],[43.44724338501692,43.24709030496423],[43.706742748618126,45.19146079878792],[44.71879083663225,42.867645127003726]]}}],\"type\":\"FeatureCollection\"}",
               50673002 "jfhxbx",
               50683002 "2",
               53363002 "[{\"text\":\"bye\"}]",
               57093002 "44.3842|44.6855|586|1kmzcxqx3",
               57103002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"758854.06\",\"pointCount\":\"6\",\"area\":\"38941915704.43\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[42.7153754979372,43.65410682496844],[42.31563717126846,45.01770027126581],[43.688070885837085,45.23405791922961],[45.4069447517395,44.4589416393743],[45.526865981519215,43.73122892181055],[44.48754720389842,43.23787603099734],[42.7153754979372,43.65410682496844]]]}}],\"type\":\"FeatureCollection\"}",
               58873002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"pointCount\":\"1\"},\"geometry\":{\"type\":\"MultiPoint\",\"coordinates\":[[43.841726928949356,44.20993576617068]]}}],\"type\":\"FeatureCollection\"}"},
     55193002 {50653003 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"1337242.38\",\"pointCount\":\"7\"},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[[44.29694976657629,44.40519672653736],[42.012350745499134,43.48753100574095],[40.53302228450776,48.21391293108405],[44.057304449379444,48.03955514592887],[45.13873390853405,47.00441785843146],[44.4252485409379,46.384848487787984],[43.1584482640028,46.46519301824055]]}}],\"type\":\"FeatureCollection\"}",
               50673002 "zgxgx",
               50683002 "1",
               53363002 "[{\"text\":\"hi\"}]",
               57093002 "44.6759|44.3829|1|1l0ds00mt",
               57103002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"length\":\"2347653.75\",\"pointCount\":\"4\",\"area\":\"328162525545.65\"},\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[[41.19146436452865,44.824926582495074],[45.881440602242954,50.17294952161225],[50.36297131329775,44.71387144987733],[45.516664534807205,42.023303031651594],[41.19146436452865,44.824926582495074]]]}}],\"type\":\"FeatureCollection\"}",
               58873002 "{\"features\":[{\"type\":\"Feature\",\"properties\":{\"pointCount\":\"3\"},\"geometry\":{\"type\":\"MultiPoint\",\"coordinates\":[[43.823340721428394,44.00183494425441],[46.13008037209511,43.396263984482566],[44.956974014639854,43.16911421642557]]}}],\"type\":\"FeatureCollection\"}"}})

  (def y (akvo.flow-services.geoshape-export/build-feature-collection nil 57103002 questions responses))
  (akvo.flow-services.geoshape-export/build-features 57103002 questions responses)




  (def xxx (org.waterforpeople.mapping.dataexport.service.BulkDataServiceClient/fetchInstanceData 57953002 "https://akvoflowsandbox.appspot.com" "nk34aR11m9"))


  (bean (. xxx surveyInstanceData))

  {"surveyedLocaleDisplayName" ""
   "surveyId"                  survey-id
   "surveyedLocaleId"          147442028
   "userID"                    1
   "collectionDate"            1418598306000
   "submitterName"             "BASH & JAMES"
   "deviceIdentifier"          "IMPORTER"
   "surveyalTime"              0
   "surveyedLocaleIdentifier"  "a6ey-5r1h-6a0y"
   "keyId"                     instance-id}

  {:surveyalTime              16,
   :surveyCode                nil,
   :collectionDate            #inst"2018-05-24T15:07:33.094-00:00",
   :surveyedLocaleIdentifier  "381t-1vk2-2ph7",
   :deviceIdentifier          "jana",
   :surveyedLocaleDisplayName "vudhxhx - 3",
   :submitterName             "jana",
   :surveyId                  58863002,
   :approvedFlag              nil,
   :class                     org.waterforpeople.mapping.app.gwt.client.surveyinstance.SurveyInstanceDto,
   :keyId                     57953002,
   :userID                    1,
   :surveyedLocaleId          56023002,
   :questionAnswersStore      nil})

;addMetaDataColumnHeader(IDENTIFIER_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(DISPLAY_NAME_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(DEVICE_IDENTIFIER_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(INSTANCE_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(SUB_DATE_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(SUBMITTER_LABEL, ++columnIdx, row);
;addMetaDataColumnHeader(DURATION_LABEL, ++columnIdx, row);


;SurveyInstanceDto dto = instanceData.surveyInstanceDto;
;int col = 0;
;createCell(r, col++, dto.getSurveyedLocaleIdentifier());
;createCell(r, col++, dto.getSurveyedLocaleDisplayName());
;createCell(r, col++, dto.getDeviceIdentifier());
;createCell(r, col++, dto.getKeyId().toString());
;createCell(r, col++, ExportImportUtils.formatDateTime(dto.getCollectionDate()));
;createCell(r, col++, sanitize(dto.getSubmitterName()));
;String duration = getDurationText(dto.getSurveyalTime());
;createCell(r, col++, duration);