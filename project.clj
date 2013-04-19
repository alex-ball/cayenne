(defproject cayenne "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :repl-options {:port 9494}
  :jvm-opts ["-Xms2G" "-Xmx18G" "-XX:+UseG1GC"]
  :repl-options {:init-ns cayenne.core}
  :resource-paths ["local/harvester2.jar", 
                   "local/log4j-1.2.12.jar",
                   "local/xalan.jar",
                   "local/xercesImpl.jar",
                   "local/xml-apis.jar"]
  :dependencies [[clojurewerkz/neocons "1.1.0-beta4"]
                 [com.novemberain/monger "1.5.0-rc1"]
                 [enlive "1.1.1"]
                 [htmlcleaner "2.2.4"]
                 [xom "1.2.5"]
                 [org.clojure/clojure "1.4.0"]
                 [org.clojure/data.json "0.2.0"]
                 [org.neo4j/neo4j "1.9.RC1"]])
