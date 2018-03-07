(ns cayenne.api.v1.schema
  (:require [ring.swagger.json-schema :refer [field]]
            [ring.swagger.swagger-ui :refer [swagger-ui]]
            [ring.swagger.swagger2 :as rs]
            [schema.core :as s]))

;; Generic
(s/defschema Query
  {:start-index s/Int :search-terms s/Str})

(s/defschema DateParts
  {:date-parts [[s/Int s/Int s/Int]]})

(s/defschema Date
  (merge DateParts {:date-time s/Inst
                    :timestamp s/Int}))

(s/defschema Message
  {:status s/Str
   :message-type s/Str
   :message-version s/Str})

(s/defschema QueryParams
  {:query {(s/optional-key :rows) (field s/Int {:description "The number of rows to return"})
           :mailto (field s/Str 
                          {:value "api-demo@crossref.org" 
                           :pattern #"^[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,6}$" 
                           :description "The email address to identify yourself and be in the \"polite pool\""})
           (s/optional-key :offset) (field s/Int {:description "The number of rows to skip before returning"})}})

(s/defschema Author
  {:ORCID s/Str
   :authenticated-orcid Boolean
   :given s/Str
   :family s/Str
   :sequence s/Str
   :affiliation [s/Str]})

;; Funders
(s/defschema FunderId (field s/Str {:description "The id of the funder"}))
(s/defschema Funder {:id FunderId,
                     :location (field s/Str {:description "The geographic location of the funder"})
                     :name s/Str
                     :alt-names (field [s/Str] {:description "Other names this funder may be identified with"})
                     :uri s/Str
                     :replaces [s/Str]
                     :replaced-by [s/Str]
                     :tokens [s/Str]})

(s/defschema FunderMessage
  (merge Message {:message-type #"funder" :message Funder}))

(s/defschema Funders
  {:items-per-page s/Int
   :query Query
   :total-results s/Int
   :items [Funder]})

(s/defschema FundersMessage
  (merge Message {:message-type #"funder-list"
                  :message Funders}))

(s/defschema
  FundersFilter
  {:query
   {:filter (field s/Str {:description "Exposes the ability to search funders by location using a Lucene based syntax"
                          :required false
                          :pattern "location:.*"})}})

;; Journals
(s/defschema JournalIssn (field [s/Str] {:description "The ISSN identifiers associated with the journal"}))
(s/defschema JournalCoverage
  {:affiliations-current s/Int
   :funders-backfile s/Int
   :licenses-backfile s/Int
   :funders-current s/Int
   :affiliations-backfile s/Int
   :resource-links-backfile s/Int
   :orcids-backfile s/Int
   :update-policies-current s/Int
   :orcids-current s/Int
   :references-backfie s/Int
   :award-numbers-backfile s/Int
   :update-policies-backfile s/Int
   :licenses-current s/Int
   :award-numbers-current s/Int
   :abstracts-backfile s/Int
   :resource-links-current s/Int
   :abstracts-current s/Int
   :references-current s/Int})

(s/defschema JournalFlags
  {:deposits-abstracts-current Boolean
   :deposits-orcids-current Boolean
   :deposits Boolean
   :deposits-affiliations-backfile Boolean
   :deposits-update-policies-backfile Boolean
   :deposits-award-numbers-current Boolean
   :deposits-resource-links-current Boolean
   :deposits-articles Boolean
   :deposits-affiliations-current Boolean
   :deposits-funders-current Boolean
   :deposits-references-backfile Boolean
   :deposits-abstracts-backfile Boolean
   :deposits-licenses-backfile Boolean
   :deposits-award-numbers-backfile Boolean
   :deposits-references-current Boolean
   :deposits-resource-links-backfile Boolean
   :deposits-orcids-backfile Boolean
   :deposits-funders-backfile Boolean
   :deposits-update-policies-current Boolean
   :deposits-licenses-current Boolean})

(s/defschema JournalCounts
  {:total-dois s/Int
   :current-dois s/Int
   :backfile-dois s/Int})

(s/defschema JournalIssnType
  {:value s/Str
   :type s/Str})

(s/defschema Journal
  {:title (field s/Str {:description "The title of the journal"}) ,
   :publisher (field s/Str {:description "The publisher of the journal"})
   :last-status-check-time s/Int
   :counts JournalCounts
   :dois-by-issued-year [[s/Int s/Int]]
   :coverage JournalCoverage
   :flags JournalFlags
   :subjects [s/Str]
   :issn-type JournalIssnType
   :ISSN JournalIssn})

(s/defschema JournalMessage
  (merge Message {:message-type #"journal" :message Journal}))

(s/defschema Journals
  {:items-per-page s/Int
   :query Query
   :total-results s/Int
   :items [Journal]})

(s/defschema JournalsMessage
  (merge Message {:message-type #"journal-list"
                  :message Journals}))

;; works
(s/defschema
  WorksSelector
  {:query
   {:select (field s/Str {:description "Exposes the ability to select certain fields from works data, supports a comma separated list of fields, e.g. DOI,volume "
                          :required false
                          :pattern #"^\w+(,\w+)*$"})}})

(s/defschema Agency {:id s/Str :label s/Str})
(s/defschema Quality {:id s/Str :description s/Str :pass Boolean})
(s/defschema WorkDoi (field [s/Str] {:description "The DOI identifier associated with the work"}))
(s/defschema WorkLink
  {:URL s/Str
   :content-type s/Str
   :content-version s/Str
   :intended-application s/Str})

(s/defschema WorkLicense
  {:URL s/Str
   :start Date
   :delay-in-days s/Int
   :content-version s/Str})

(s/defschema WorkDomain
  {:domain [s/Str]
    :crossmark-restriction Boolean})

(s/defschema WorkReview
  {:type s/Str
   :running-number s/Str
   :revision-round s/Str
   :stage s/Str
   :competing-interest-statement s/Str
   :recommendation s/Str
   :language s/Str})

(s/defschema WorkInstitution
  {:name s/Str
   :place [s/Str]
   :department [s/Str]
   :accronym [s/Str]})

(s/defschema Work
  {:indexed Date
   (s/optional-key :institution) WorkInstitution
   :reference-count s/Int
   :publisher s/Str
   :issue s/Str
   :content-domain WorkDomain
   :short-container-title s/Str
   :published-print DateParts
   :DOI WorkDoi
   :type s/Str
   :created Date
   :license [WorkLicense]
   :page s/Str
   :source s/Str
   :is-reference-by-count s/Int
   :title [s/Str]
   (s/optional-key :original-title) [s/Str]
   :short-title [s/Str]
   :prefix s/Str
   :volume s/Str
   :member s/Str
   :container-title [s/Str]
   :link [WorkLink]
   :deposited Date
   :score Long
   :author [Author]
   :URL s/Str
   :references-count s/Int
   (s/optional-key :review) WorkReview})

(s/defschema WorkMessage
  (merge Message
         {:message Work}))

(s/defschema Works
  {:items-per-page s/Int
   :query Query
   :total-results s/Int
   :items [Work]})

(s/defschema WorksMessage
  (merge Message
         {:message-type #"work-list"
          :message Works}))

(s/defschema AgencyMessage
  (merge Message {:message-type #"work-agency" :message {:DOI s/Str :agency Agency}}))

(s/defschema QualityMessage
  (merge Message {:message-type #"work-quality" :message [Quality]}))
