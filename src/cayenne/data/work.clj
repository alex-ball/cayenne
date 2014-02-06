(ns cayenne.data.work
  (:require [cayenne.conf :as conf]
            [cayenne.api.v1.query :as query]
            [cayenne.api.v1.response :as r]
            [cayenne.api.v1.filter :as filter]
            [cayenne.data.quality :as quality]
            [cayenne.action :as action]
            [cayenne.formats.citeproc :as citeproc]
            [somnium.congomongo :as m]
            [clojure.string :as string]))

;; todo eventually produce citeproc from more detailed data stored in mongo
;; for each DOI that comes back from solr. For now, covert the solr fields
;; to some (occasionally ambiguous) citeproc structures.

;; todo API links - orcids, subject ids, doi, issn, isbn, owner prefix

;; todo conneg. currently returning two different formats - item-tree
;; where a DOI is known, citeproc for search results.

(defn fetch [query-context]
  (let [doc-list (-> (conf/get-service :solr)
                     (.query (query/->solr-query query-context 
                                                 :filters filter/std-filters))
                     (.getResults))]
    (-> (r/api-response :work-list)
        (r/with-result-items (.getNumFound doc-list) (map citeproc/->citeproc doc-list))
        (r/with-query-context-info query-context))))

(defn fetch-one
  "Fetch a known DOI."
  [doi-uri]
  (let [docs (-> (conf/get-service :solr)
                 (.query (query/->solr-query {:id doi-uri}
                                             :id-field "doi"))
                 (.getResults))]
    (r/api-response :work :content (citeproc/->citeproc (first docs)))))

(defn get-unixsd [doi]
  (let [record (promise)]
    (action/parse-doi doi (action/return-item record))
    (second @record)))

(defn fetch-quality
  [doi]
  (let [item-tree (get-unixsd doi)]
    (r/api-response :work-quality :content (quality/check-tree item-tree))))
  
