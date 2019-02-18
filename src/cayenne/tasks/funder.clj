(ns cayenne.tasks.funder
  (:require [clojure.string :as string]
            [cayenne.item-tree :as itree]
            [cayenne.rdf :as rdf]
            [cayenne.conf :as conf]
            [cayenne.util :as util]
            [cayenne.tasks.geoname :as geoname]
            [qbits.spandex :as elastic]
            [cayenne.elastic.util :as elastic-util]))

(def svf-el (partial rdf/get-property "http://www.elsevier.com/xml/schema/grant/grant-1.2/"))
(def svf-cr (partial rdf/get-property "http://data.crossref.org/fundingdata/xml/schema/grant/grant-1.2/"))

(defn find-funders [model]
  (-> (rdf/select model
                  :predicate (rdf/rdf model "type")
                  :object (rdf/skos-type model "Concept"))
      (rdf/subjects)))

(defn res->id [funder-concept-node]
  (when funder-concept-node
    (-> funder-concept-node
        rdf/->uri
        (string/split #"/")
        last)))

(defn res->doi [funder-concept-node]
  (when funder-concept-node
    (str
     "10.13039/"
     (-> funder-concept-node
         rdf/->uri
         (string/split #"/")
         last))))

(defn get-labels [model node kind]
  (->> (rdf/select model :subject node :predicate (rdf/skos-xl model kind))
       (rdf/objects)
       (mapcat #(rdf/select model :subject % :predicate (rdf/skos-xl model "literalForm")))
       (rdf/objects)
       (map #(.getString %))))

(defn select-country-stmts [model node]
  (concat
   (rdf/select model
               :subject node
               :predicate (svf-el model "country"))
   (rdf/select model
               :subject node
               :predicate (svf-cr model "country"))))

(defn get-country-literal-name [model node]
  (let [country-obj (-> (select-country-stmts model node)
                        (rdf/objects)
                        (first))]
    (if (or (nil? country-obj)
            (= (rdf/->uri country-obj)
               "http://sws.geonames.org//"))
      (do
        (prn "Found node with no country: " node)
        "Unknown")
      (try
        (-> country-obj
            (rdf/->uri)
            (str "about.rdf")
            (geoname/get-geoname-name-memo))
        (catch Exception e nil)))))

(defn broader [model funder-resource & {:keys [objects-only] :or {objects-only false}}]
  (concat
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (rdf/skos model "broader")))
   (if objects-only
     []
     (rdf/subjects
      (rdf/select model :predicate (rdf/skos model "narrower") :object funder-resource)))))

(defn narrower [model funder-resource & {:keys [objects-only] :or {objects-only false}}]
  (concat
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (rdf/skos model "narrower")))
   (if objects-only
     []
     (rdf/subjects
      (rdf/select model :predicate (rdf/skos model "broader") :object funder-resource)))))

(defn replaces [model funder-resource]
  (concat
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (rdf/dct model "replaces")))
   (rdf/subjects
    (rdf/select model :predicate (rdf/dct model "isReplacedBy") :object funder-resource))))

(defn replaced-by [model funder-resource]
  (concat
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (rdf/dct model "isReplacedBy")))
   (rdf/subjects
    (rdf/select model :predicate (rdf/dct model "replaces") :object funder-resource))))

(defn affiliated [model funder-resource]
  (concat
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (svf-el model "affilWith")))
   (rdf/objects
    (rdf/select model :subject funder-resource :predicate (svf-cr model "affilWith")))))

(defn resource-ancestors [model funder-resource]
  (drop 1 (tree-seq (constantly true) #(broader model %) funder-resource)))

(defn resource-descendants [model funder-resource]
  (drop 1 (tree-seq (constantly true) #(narrower model %) funder-resource)))

(defn id-name [model funder-resource]
  (merge
   {:name (first (get-labels model funder-resource "prefLabel"))
    :id (res->id funder-resource)}
   (if (not-empty (narrower model funder-resource :objects-only true))
     {:more true})))

(defn- hierarcy-node [model funder-resource descendants child]
  {(keyword (res->id funder-resource))
   (merge
    (dissoc (id-name model funder-resource) :more)
    (reduce (fn [m v]
              (update m
                      (keyword (res->id v))
                      #(or % (id-name model v))))
            child
            descendants))})

(defn- build-hierarchy [model funder-resource child]
  (let [ancestors (broader model funder-resource :objects-only true)
        descendants (narrower model funder-resource :objects-only true)]
    (if (not-empty ancestors)
      (build-hierarchy model (first ancestors) (hierarcy-node model funder-resource descendants child))
      (hierarcy-node model funder-resource descendants child))))

(defn index-command
  "Build an Elastic Search object from a resource in the context of a model.
   The unique ID is taken from the Resource ID."
  [model funder-resource]
  (let [primary-name   (-> model (get-labels funder-resource "prefLabel") first)
        alt-names      (-> model (get-labels funder-resource "altLabel"))
        ancestors      (resource-ancestors model funder-resource)
        descendants    (resource-descendants model funder-resource)
        ancestor-ids   (->> ancestors (map res->id) distinct sort)
        descendant-ids (->> descendants (map res->id) distinct sort)
        level          (-> ancestor-ids count (+ 1))
        hierarchy      (build-hierarchy model funder-resource (id-name model funder-resource))]
    [{:index {:_id (res->id funder-resource)}}
     {:doi             (res->doi funder-resource)
      :id              (res->id funder-resource)
      :primary-name    primary-name
      :name            alt-names
      :token           (concat
                        (util/tokenize-name primary-name)
                        (flatten (map util/tokenize-name alt-names)))
      :country         (get-country-literal-name model funder-resource)
      :parent          (-> model (broader funder-resource) first res->doi)
      :ancestor        ancestor-ids
      :level           level
      :child           (distinct (map res->id (narrower model funder-resource)))
      :descendant      descendant-ids
      :affiliated      (distinct (map res->id (affiliated model funder-resource)))
      :replaced-by     (distinct (map res->id (replaced-by model funder-resource)))
      :replaces        (distinct (map res->id (replaces model funder-resource)))
      :hierarchy-names (reduce
                        (fn [m [k v]]
                          (assoc m k v))
                        (if (> level 1) {:more nil} {})
                        (partition 2 (util/get-all-in hierarchy [:id :name])))
      :hierarchy       hierarchy}]))

(defn index-funders
  "Retrieve funder information RDF and index into Elastic."
  []
  (let [model (-> (java.net.URL. (conf/get-param [:location :cr-funder-registry]))
                  rdf/document->model)]
    (doseq [funders (->> model
                         find-funders
                         (partition-all 100))]
      (elastic/request
       (conf/get-service :elastic)
       {:method :post
        :url "/funder/funder/_bulk"
        :body (->> funders
                   (map (partial index-command model))
                   flatten
                   elastic-util/raw-jsons)}))))

;; Funder RDF inspection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;::::::

(defn rdf->funder-names [rdf-file]
  (let [model (rdf/document->model rdf-file)
        funders (find-funders model)]
    (map #(first (get-labels %1 %2 "prefLabel")) (repeat model) funders)))

(defn rdf->funder-ids [rdf-file]
  (->> rdf-file
       (rdf/document->model)
       (find-funders)
       (map res->doi)))

(defn diff-funders-rdf-names
  "Returns a list of funder names found in the new RDF file but not in the old
   RDF file."
  [old-rdf-file new-rdf-file]
  (let [old-funder-names (set (rdf->funder-names old-rdf-file))
        new-funder-names (set (rdf->funder-names new-rdf-file))]
    (clojure.set/difference new-funder-names old-funder-names)))

(defn diff-funders-rdf-ids
  "Returns a list of funder IDs found in the new RDF file but not in the old
   RDF file."
  [old-rdf-file new-rdf-file]
  (let [old-funder-ids (set (rdf->funder-ids old-rdf-file))
        new-funder-ids (set (rdf->funder-ids new-rdf-file))]
    (clojure.set/difference new-funder-ids old-funder-ids)))

(defn stat-funders-rdf
  "Generate some statistics on funder concepts in RDF."
  [rdf-file]
  (let [concept-ids (rdf->funder-ids rdf-file)]
    {:concepts (count concept-ids)
     :unique-ids (count (set concept-ids))}))
