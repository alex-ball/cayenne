(ns cayenne.data.member
  (:require [cayenne.conf :as conf]
            [cayenne.api.v1.query :as query]
            [cayenne.api.v1.response :as r]
            [cayenne.api.v1.facet :as facet]
            [cayenne.api.v1.filter :as filter]
            [cayenne.data.prefix :as prefix]
            [cayenne.data.work :as work]
            [cayenne.ids.member :as member-id]
            [cayenne.ids.prefix :as prefix-id]
            [cayenne.formats.citeproc :as citeproc]
            [somnium.congomongo :as m]
            [clojure.string :as string]))

(def solr-publisher-id-field "owner_prefix")

(defn get-solr-works [query-context]
  (-> (conf/get-service :solr)
      (.query (query/->solr-query query-context
                                  :filters filter/std-filters))))

(defn get-solr-work-count [query-context]
  (-> (conf/get-service :solr)
      (.query (query/->solr-query query-context
                                  :paged false
                                  :count-only true))
      (.getResults)
      (.getNumFound)))

(defn get-id-from-context [query-context]
  (-> query-context
      (:id)
      (member-id/extract-member-id)
      (Integer/parseInt)))

(defn expand-context-for-prefixes [query-context]
  (let [member-doc (m/with-mongo (conf/get-service :mongo)
                     (m/fetch-one 
                      "members" 
                      :where {:id (get-id-from-context query-context)}))
        prefixes (:prefixes member-doc)
        prefixes-filter (->> prefixes
                             (map prefix-id/to-prefix-uri)
                             (map #(filter/field-is-esc solr-publisher-id-field %))
                             (apply filter/q-or))]
    (assoc query-context :raw-filter prefixes-filter)))

(defn ->response-doc [pub-doc]
  {:id (:id pub-doc)
   :primary-name (:primary-name pub-doc)
   :names (:names pub-doc)
   :prefixes (:prefixes pub-doc)
   :prefix (:prefix pub-doc)
   :location (:location pub-doc)
   :flags (:flags pub-doc)
   :coverage (:coverage pub-doc)
   :coverage-type (:coverage-type pub-doc)
   :breakdowns (:breakdowns pub-doc)
   :counts (:counts pub-doc)
   :counts-type (:counts-type pub-doc)
   :last-status-check-time (:last-status-check-time pub-doc)
   :tokens (:tokens pub-doc)})

(defn parse-query-terms 
  "Split query terms."
  [terms]
  (when terms
    (-> terms
        (string/lower-case)
        (string/replace #"[,\.\-\'\"]" "")
        (string/split #"\s+"))))

(defn fetch-one [query-context]
  (let [pub-doc (m/with-mongo (conf/get-service :mongo)
                  (m/fetch-one 
                   "members"
                   :where {:id (get-id-from-context query-context)}))]
    (when pub-doc
      (r/api-response :member
                      :content
                      (->response-doc pub-doc)))))

(defn fetch-works [query-context]
  (-> query-context
      expand-context-for-prefixes
      work/fetch))

;; todo handle rows and offset propery (not using either of them)

(defn fetch [query-context]
  (let [parsed-terms (or (parse-query-terms (:terms query-context)) [])
        and-list (map #(hash-map "tokens" {"$regex" (str "^" %)}) parsed-terms)
        where-clause (if (empty? and-list) {} {"$and" and-list})
        mongo-query (query/->mongo-query query-context 
                                         :filters filter/member-filters
                                         :where where-clause
                                         :sort {:id 1})
        docs (if (and (:rows query-context) (zero? (:rows query-context)))
               []
               (m/with-mongo (conf/get-service :mongo)
                 (apply m/fetch "members" mongo-query)))
         result-count (m/with-mongo (conf/get-service :mongo)
                        (apply m/fetch-count "members" mongo-query))]
    (-> (r/api-response :member-list)
        (r/with-query-context-info query-context)
        (r/with-result-items result-count (map ->response-doc docs)))))
                        
