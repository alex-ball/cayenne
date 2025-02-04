(ns cayenne.tasks
  (:require [cayenne.tasks.publisher :as publisher]
            [cayenne.tasks.journal :as journal]
            [cayenne.tasks.funder :as funder]
            [cayenne.tasks.coverage :as coverage]
            [cayenne.data.work :as work]
            [cayenne.action :as action]
            [cayenne.conf :as conf]
            [cayenne.production :as production]
            [clj-time.core :as time]
            [clj-time.format :as timef]))

(defn setup []
  (conf/create-core-from! :task :default)
  (conf/set-core! :task)
  (production/apply-env-overrides :task)
  (conf/start-core! :task))

(defn load-funders [& args]
  (setup)
  (funder/clear!)
  (funder/drop-loading-collection)
  (funder/load-funders-rdf (java.net.URL. (conf/get-param [:location :cr-funder-registry])))
  (funder/swapin-loading-collection))

(defn load-journals [& args]
  (let [collection-name (or (first args) "journals")]
    (setup)
    (journal/load-journals-from-cr-title-list-csv collection-name)
    (coverage/check-journals collection-name)))

(defn load-members [& args]
  (let [collection-name (or (first args) "members")]
    (setup)
    (publisher/load-publishers collection-name)
    (coverage/check-members collection-name)))

(defn load-last-day-works [& args]
  (setup)
  (let [oai-date-format (timef/formatter "yyyy-MM-dd")
        from (timef/unparse oai-date-format (time/minus (time/today-at-midnight) (time/days 2)))
        until (timef/unparse oai-date-format (time/today-at-midnight))]
    (doseq [oai-service [:crossref-journals :crossref-serials :crossref-books]]
      (action/get-oai-records
       (conf/get-param [:oai oai-service]) from until action/index-solr-docs))))

(defn update-old-index-docs [& args]
  (setup)
  (let [dois (->> {:rows (int 10)
                   :select ["DOI"]
                   :filters {"until-index-date" args}}
                  work/fetch
                  :message
                  :items
                  (map :DOI))]
    (println "Updating" (count dois) "DOIs")
    (doseq [doi dois]
      (action/parse-doi doi action/index-solr-docs))
    (println "Done")))
