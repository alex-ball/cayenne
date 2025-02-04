(ns cayenne.ids.type)

(defn bibo-type [clss]
  (str "http://purl.org/ontology/bibo/" clss))

(def type-dictionary {:journal-article {:index-id "Journal Article"
                                        :label "Journal Article"
                                        :bibo-type (bibo-type "Article")
                                        :csl-type "article-journal"
                                        :rpp-type "Journal Articles"}
                      :journal-issue {:index-id "Journal Issue"
                                      :label "Journal Issue"
                                      :bibo-type (bibo-type "Issue")}
                      :journal-volume {:index-id "Journal Volume"
                                       :label "Journal Volume"
                                       :bibo-type (bibo-type "Collection")}
                      :journal {:index-id "Journal"
                                :label "Journal"
                                :bibo-type (bibo-type "Journal")}
                      :proceedings-article {:index-id "Conference Paper"
                                            :label "Proceedings Article"}
                      :proceedings-series {:index-id "Proceedings Series"
                                           :label "Proceedings Series"}
                      :proceedings {:index-id "Proceedings"
                                    :label "Proceedings"}
                      :dataset {:index-id "Dataset"
                                :label "Dataset"}
                      :component {:index-id "Component"
                                  :label "Component"}
                      :report {:index-id "Report"
                               :label "Report"
                               :bibo-type (bibo-type "Report")}
                      :report-series {:index-id "Report Series"
                                      :label "Report Series"}
                      :standard {:index-id "Standard"
                                 :label "Standard"}
                      :standard-series {:index-id "Standard Series"
                                        :label "Standard Series"}
                      :edited-book {:index-id "Edited Book"
                                    :label "Edited Book"}
                      :monograph {:index-id "Monograph"
                                  :label "Monograph"}
                      :reference-book {:index-id "Reference"
                                       :label "Reference Book"}
                      :book {:index-id "Book"
                             :label "Book"}
                      :book-series {:index-id "Book Series"
                                    :label "Book Series"}
                      :book-set {:index-id "Book Set"
                                 :label "Book Set"}
                      :book-chapter {:index-id "Chapter"
                                     :label "Book Chapter"}
                      :book-section {:index-id "Section"
                                     :label "Book Section"}
                      :book-part {:index-id "Part"
                                  :label "Part"}
                      :book-track {:index-id "Track"
                                   :label "Book Track"}
                      :reference-entry {:index-id "Entry"
                                   :label "Reference Entry"}
                      :dissertation {:index-id "Dissertation"
                                     :label "Dissertation"}
                      :posted-content {:index-id "Posted Content"
                                       :label "Posted Content"}
                      :peer-review {:index-id "Peer Review"
                                    :crossmark-unaware? true
                                    :label "Peer Review"}
                      :other {:index-id "Other"
                              :label "Other"}})

(def reverse-dictionary
  (reduce
   (fn [m [key value]]
     (assoc m (:index-id value) key))
   {}
   type-dictionary))

(defn ->type-id [index-str]
  (get reverse-dictionary index-str))

(defn ->index-id [id]
  (when-let [t (get type-dictionary (keyword id))]
    (:index-id t)))
