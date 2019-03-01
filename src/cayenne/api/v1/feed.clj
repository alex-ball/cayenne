(ns cayenne.api.v1.feed
  "Feed ingests XML files that are pushed into Cayenne.
   A directory structure of input and output files is expected: 'in', 'processed' and 'failed'."
  (:require [cayenne.conf :as conf]
            [cayenne.xml :as xml]
            [cayenne.formats.unixsd :as unixsd]
            [cayenne.item-tree :as itree]
            [cayenne.tasks.funder :as funder]
            [cayenne.api.v1.types :as types]
            [cayenne.api.v1.response :as r]
            [cayenne.api.v1.update :refer [read-updates-message update-as-elastic-command]]
            [compojure.core :refer [defroutes ANY]]
            [liberator.core :refer [defresource]]
            [clojure.string :as string]
            [clojure.java.io :refer [reader writer] :as io]
            [taoensso.timbre :as timbre :refer [info error]]
            [digest :as digest]
            [metrics.meters :refer [defmeter] :as meter]
            [nio2.dir-seq :refer [dir-seq-glob]]
            [nio2.io :refer [path]]
            [clj-time.core :as dt]
            [clojure.core.async :refer [chan buffer go go-loop <! >!!]]
            [cayenne.elastic.index :as es-index]
            [cayenne.elastic.update :as es-update])
  (:import [java.util UUID]
           [java.io File]
           [java.util.concurrent TimeUnit]))

(def feed-content-types #{"application/vnd.crossref.unixsd+xml"
                          "application/vnd.crossref.update+json"})

(def content-type-mnemonics
  {"application/vnd.crossref.unixsd+xml" "unixsd"
   "application/vnd.crossref.update+json" "update"})

(def content-type-mnemonics-reverse
  {"unixsd" "application/vnd.crossref.unixsd+xml"
   "update" "application/vnd.crossref.update+json"})

(def feed-providers #{"crossref"})

(def provider-names {"crossref" "Crossref"})

(defn feed-log [f state]
  (spit
   (str (conf/get-param [:dir :data]) "/feed.log")
   (str (dt/now) " " f " - " state "\n")
   :append true))

(defn feed-thread-log [msg]
  (spit
   (str (conf/get-param [:dir :data]) "/feed-thread.log")
   (str (dt/now) " " msg "\n")
   :append true))

(defmeter [cayenne feed files-received] "files-received")

(defn new-id [] (UUID/randomUUID))

(defn feed-in-dir []
  (str (conf/get-param [:dir :data]) "/feed-in"))

(defn feed-filename
  "Filenames are expected to take a prescribed pattern in an expected directory structure."
  [stage-name content-type provider id]
  {:pre [(#{"in" "processed" "failed"} stage-name )]}
  (str (conf/get-param [:dir :data])
       "/feed-" stage-name
       "/" provider
       "-" (content-type-mnemonics content-type)
       "-" id
       ".body"))

(defn parse-feed-filename [filename]
  (let [[provider content-type & rest] (-> filename
                                           (string/split #"/")
                                           last
                                           (string/split #"-"))
        id (-> (string/join "-" rest)
               (string/split #"\.")
               first)]
    {:content-type (content-type-mnemonics-reverse content-type)
     :provider provider
     :id id}))

(defn incoming-file [feed-context]
  (feed-filename "in"
                 (:content-type feed-context)
                 (:provider feed-context)
                 (:id feed-context)))

(defn processed-file [feed-context]
  (feed-filename "processed"
                 (:content-type feed-context)
                 (:provider feed-context)
                 (:id feed-context)))

(defn failed-file [feed-context]
  (feed-filename "failed"
                 (:content-type feed-context)
                 (:provider feed-context)
                 (:id feed-context)))

(defn move-file! [from to]
  (let [from-file (File. from)
        to-file (File. to)]
    (.mkdirs (.getParentFile to-file))
    (.renameTo from-file to-file)))

(defn make-feed-context
  "A feed context describes a single input file's attributes."
  ([content-type provider doi]
   (let [base {:content-type content-type
               :provider provider
               :doi doi
               :id (new-id)}]
     (-> base
         (assoc :incoming-file (incoming-file base))
         (assoc :processed-file (processed-file base))
         (assoc :failed-file (failed-file base)))))
  ([filename]
   (let [base (parse-feed-filename filename)]
     (-> base
         (assoc :incoming-file (incoming-file base))
         (assoc :processed-file (processed-file base))
         (assoc :failed-file (failed-file base))))))

(defn process-with
  "Processes an input file according to supplied function.
   Builds a reader over the input file, passes to the function, then moves to failed or processed dir."
  [process-fn feed-context]
  (with-open [rdr (reader (:incoming-file feed-context))]
    (try
      (do
        (process-fn rdr)
        (move-file! (:incoming-file feed-context)
                    (:processed-file feed-context)))
      (catch Exception e
        (feed-log (:incoming-file feed-context) (str "Exception while processing file: " (.getMessage e)))
        (error e (str "Failed to process feed file " (:incoming-file feed-context)))
        (move-file! (:incoming-file feed-context)
                    (:failed-file feed-context))))))
  
(defmulti process! :content-type)

(defmethod process! "application/vnd.crossref.unixsd+xml" [feed-context filename]
  (process-with
   (fn [rdr]
     ; xml/process-xml returns the document but we use the callback to do the work.
     (let [f #(let [parsed (->> %
                           unixsd/unixsd-record-parser
                           (apply itree/centre-on))
                   doi (first (itree/get-item-ids parsed :long-doi))]
                   (es-index/index-item parsed)
                   (feed-log filename (str "parsed file for DOI:" doi)))]

          (xml/process-xml rdr "crossref_result" f)))
   feed-context))

(defmethod process! "application/vnd.crossref.update+json" [feed-context filename]
  (process-with
   #(->> %
         read-updates-message
         (map update-as-elastic-command)
         es-update/index-updates)
   feed-context))

(defn process-feed-file! [f]
  (let [filename (.getName f)]
    (try
      (let [context (-> f .getAbsolutePath make-feed-context)]

        (feed-log filename "Preparing to process")
        (process! context filename)
        (feed-log filename "Processed"))

      (catch Exception e
        (feed-log filename "Failed")
        (error e (str "Failed to process feed file " f))))))


(defn record! [feed-context body]
  (let [incoming-file (-> feed-context :incoming-file io/file)]
    (feed-log (.getName incoming-file) "Receiving")
    (.mkdirs (.getParentFile incoming-file))
    (io/copy body incoming-file)
    (meter/mark! files-received)
    (feed-log (.getName incoming-file)  (str "Received and stored for doi:" (:doi feed-context)))
    (assoc feed-context :digest (digest/md5 incoming-file))))

(defn strip-content-type-params [ct]
  (-> ct
      (string/split #";")
      first
      string/trim))

(defresource feed-resource [provider]
  :allowed-methods [:post :options]
  :available-media-types types/json
  :known-content-type? #(some #{(-> %
                                    (get-in [:request :headers "content-type"])
                                    strip-content-type-params)}
                              feed-content-types)
  :exists? (fn [_] (some #{provider} feed-providers))
  :new? true
  :post! #(let [result (-> %
                           (get-in [:request :headers "content-type"])
                           strip-content-type-params
                           (make-feed-context provider (get-in % [:request :headers "doi"]))
                           (record! (get-in % [:request :body])))]
            (assoc % :digest (:digest result)))
  :handle-created #(r/api-response :feed-file-creation
                                   :content {:digest (:digest %)}))

(defroutes feed-api-routes
  (ANY "/:provider" [provider]
       (feed-resource provider)))

(def feed-file-chan (chan (buffer 1000)))

(defn start-feed-processing []
  (feed-thread-log (str "Start with concurrency " (conf/get-param [:val :feed-concurrency])))
  (dotimes [n (conf/get-param [:val :feed-concurrency])]
    (go-loop []
      (try
        (feed-thread-log (str "Go loop #" n " iteration getting a job"))
        (let [f (<! feed-file-chan)]
          (feed-thread-log (str "Go loop #" n " iteration got a job - " (.getName f)))
          (when (.exists f)
            (process-feed-file! f)))
        (catch Exception e
          (error e "Failed to check file existence")))
      (recur)))
  (.mkdirs (File. (feed-in-dir)))
  (doto (conf/get-service :executor)
    (.scheduleWithFixedDelay
     (fn []
       (try
         (doseq [p (dir-seq-glob (path (feed-in-dir)) "*.body")]
           (>!! feed-file-chan (.toFile p)))
         (catch Exception e
           (error e "Could not iterate feed-in files"))))
     0
     5000
     TimeUnit/MILLISECONDS)))

(conf/with-core :default
  (conf/add-startup-task
   :process-feed-files
   (fn [profiles]
     (start-feed-processing))))
