(defproject network.mangadex/md-client "1.0.0"
  :description "Mangadex@Home Client"
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [aleph "0.4.6"]
                 [buddy/buddy-core "1.6.0"]
                 [compojure "1.6.1"]
                 [cheshire "5.10.0"]
                 [shutdown "0.1.0-SNAPSHOT"]
                 [lambdaisland/uri "1.3.45"]
                 [com.jakewharton/disklrucache "2.0.2"]
                 [com.taoensso/timbre "4.10.0"]
                 [com.taoensso/nippy "2.14.0"]
                 [fullspectrum/influxdb-client "1.0.0"]
                 [javax.xml.bind/jaxb-api "2.2.4"]
                 ]
  :profiles {:uberjar {:aot :all}}
  :main mangadex.client.core
  )

