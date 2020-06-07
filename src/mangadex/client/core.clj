(ns mangadex.client.core
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :refer [join lower-case]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [byte-streams :refer [convert stream-of]]
            [manifold.time :refer [every seconds minutes hours]]
            [manifold.deferred :as d]
            [manifold.stream :refer [buffered-stream consume put! on-drained closed?]]
            [aleph.http :as http]
            [aleph.http.client-middleware :refer [default-middleware]]
            [aleph.netty :as netty]
            [compojure.core :refer [defroutes GET]]
            [lambdaisland.uri :as uri]
            [buddy.core.keys.pem :as pem]
            [buddy.core.hash :refer [md5]]
            [shutdown.core :as shutdown]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.3rd-party.rotor :refer [rotor-appender]]
            [taoensso.nippy :as nippy]
            [influxdb.client :as influx]
            [influxdb.convert :refer [point->line]]
            )
  (:import [java.util Base64]
           [java.util.concurrent Executors]
           [javax.xml.bind DatatypeConverter]
           [javax.crypto Cipher CipherInputStream CipherOutputStream]
           [javax.crypto.spec SecretKeySpec]
           [io.netty.handler.ssl SslContextBuilder]
           [io.netty.handler.traffic GlobalTrafficShapingHandler]
           [io.netty.channel ChannelInboundHandlerAdapter]
           [io.netty.util.concurrent GlobalEventExecutor]
           [com.jakewharton.disklrucache DiskLruCache]
           )
  )

(def tls-atom (atom nil))
(comment {:cert ""
          :key  ""
          :time ""
          })

(def server-atom (atom nil)) ;; AlephServer
(def upstream-atom (atom nil)) ;; Upstream URL
(def shutdown-state (atom false))

(def connection-count (atom 0))
(def request-count (atom 0))
(def hit-count (atom 0))

(defn encode64 [b]
  (.encodeToString (Base64/getEncoder) b))

(defn decode64 [b]
  (.decode (Base64/getDecoder) b))

(defn get-cipher [algo secret]
  (doto (Cipher/getInstance algo)
    (.init Cipher/ENCRYPT_MODE (SecretKeySpec. secret algo))))

(defn format-pem-string [encoded]
  (let [chunked (->> encoded
                     (partition 64 64 [])
                     (map #(apply str %)))
        formatted (join "\n" chunked)]
    (str "-----BEGIN PRIVATE KEY-----\n"
         formatted
         "\n-----END PRIVATE KEY-----\n")))

(defn -main []

  ;; Global exception handler
  (Thread/setDefaultUncaughtExceptionHandler
    (reify Thread$UncaughtExceptionHandler
      (uncaughtException [_ thread ex]
        (log/error ex "Uncaught exception on" (.getName thread)))))

  (println "=== Mangadex@Home Client =======================================")
  (let [config (edn/read-string (slurp "config.edn"))
        burst-limit (-> config :burst-limit (* 1024))
        egress-limit (-> config :egress-limit (* 1024 1024))
        executor (Executors/newScheduledThreadPool 1)
        bandwidth-limiter (GlobalTrafficShapingHandler. executor burst-limit 0)
        traffic-counter (.trafficCounter bandwidth-limiter)
        cache-size (-> config :cache-size (* 1024 1024))
        cache (DiskLruCache/open (io/file "data") 0 2 cache-size)
        ]
    (pprint (dissoc config :secret)) ;; Print config for debugging
    (println "================================================================")

    (when (:file-logging config)
      (log/merge-config! {:appenders {:file (rotor-appender {:path "log/md-client.log"})}}))

    (defroutes http-handler
      (GET "/data/:chapter/:image" [chapter image :as req]
           (log/info (str "GET " (:uri req)))
           (swap! request-count inc)
           (let [cache-key (md5 (str chapter image))
                 cache-key-str (lower-case (DatatypeConverter/printHexBinary cache-key))
                 rc4 (get-cipher "RC4" cache-key)]
             (if-let [entry (.get cache cache-key-str)]
               (do (swap! hit-count inc)
                   {:body (-> entry (.getInputStream 0) (CipherInputStream. rc4))
                    :headers (-> entry (.getString 1) decode64 nippy/thaw)
                    })
               (do (log/info (str "Cache miss, fetching from " @upstream-atom))
                   (d/chain (http/get (str (uri/join @upstream-atom (str "/data/" chapter "/" image))))
                            (fn [{:keys [body headers status]}]
                              (let [content-length (-> headers
                                                       (get "content-length")
                                                       edn/read-string)
                                    cache-entry (-> cache (.edit cache-key-str))]
                                (cond
                                  (not= status 200)     {:body body :headers headers}
                                  (not content-length)  {:body body :headers headers}
                                  (not cache-entry)     {:status 500}
                                  :else
                                  (let [stored-headers (select-keys headers ["content-length"
                                                                             "content-type"
                                                                             "etag"])
                                        counter (atom 0)
                                        cache-stream (-> cache-entry
                                                         (.newOutputStream 0)
                                                         (CipherOutputStream. rc4))
                                        in-stream (convert body (stream-of bytes) {:chunk-size 4096})
                                        out-stream (buffered-stream alength content-length)
                                        ]
                                    (consume
                                      (fn [arr]
                                        (swap! counter + (alength arr))
                                        (.write cache-stream arr)
                                        (when (not (closed? out-stream))
                                          (put! out-stream arr)))
                                      in-stream)
                                    (on-drained
                                      in-stream
                                      (fn []
                                        (.close cache-stream)
                                        (if (= content-length @counter)
                                          (do (->> stored-headers nippy/freeze encode64 (.set cache-entry 1))
                                              (.commit cache-entry)
                                              (log/info (str "Cache commit: " chapter "/" image)))
                                          (do (.abort cache-entry)
                                              (log/warn (str "Cache abort: " chapter "/" image))))))
                                    {:body out-stream
                                     :headers stored-headers})))
                              )))
               ))))

    (defn shutdown-node []
      (when-let [server @server-atom]
        @(http/post (-> config :api-server (uri/join "/stop") str)
                    {:content-type :json 
                     :form-params {:secret (:secret config)}})
        (reset! server-atom nil)
        (.close server)
        (netty/wait-for-close server)
        (log/info "Stopped Mangadex@Home node")))

    (add-watch
      tls-atom :tls-update
      (fn [_ _ _ tls]
        (try
          (when tls
            (shutdown-node)
            (let [ssl-ctx (-> (SslContextBuilder/forServer
                                (-> tls :cert .getBytes io/input-stream)
                                (-> tls :key .getBytes (pem/read-privkey "")
                                    .getEncoded encode64 format-pem-string
                                    .getBytes io/input-stream))
                              (.build))
                  server (http/start-server
                           http-handler
                           {:port (:https-port config)
                            :ssl-context ssl-ctx
                            :shutdown-executor? false
                            :pipeline-transform 
                            (fn [pipeline]
                              (.addFirst pipeline bandwidth-limiter)
                              (.addFirst pipeline ;; Connection counter
                                         (proxy [ChannelInboundHandlerAdapter] []
                                           (channelActive [ctx]
                                             (swap! connection-count inc)
                                             (.fireChannelActive ctx))
                                           (channelInactive [ctx]
                                             (swap! connection-count dec)
                                             (.fireChannelInactive ctx)))))
                            })]
              (log/info "Started Mangadex@Home node")
              (reset! server-atom server)))
          (catch Exception ex
            (log/error ex)))))

    (when-let [influx-url (:influx-metrics config)]
      (every
        (seconds 1)
        (fn [] ;; Collect stats
          (let [byte-count (.cumulativeWrittenBytes traffic-counter)
                throughput (.lastWriteThroughput traffic-counter)
                line (point->line {:meas "mangadex"
                                   :fields {:egress byte-count
                                            :throughput throughput
                                            :connection @connection-count
                                            :request @request-count
                                            :hit @hit-count
                                            :cacheSize (.size cache)
                                            }})]
            (when (and (not @shutdown-state)
                       (not= egress-limit 0)
                       (> byte-count egress-limit))
              (log/info "Hourly limit exceeded - taking node offline...")
              (reset! shutdown-state true)
              (.resetCumulativeTime traffic-counter)
              (future (shutdown-node)))
            (influx/write {:url influx-url} "mangadex" line)))))

    (every
      (hours 1)
      (fn [] 
        (when @shutdown-state
          (reset! tls-atom nil)
          (reset! shutdown-state false))))
    
    (every
      (minutes 1)
      (fn []
        (when-not @shutdown-state
          (try
            (let [url (-> config :api-server (uri/join "/ping") str)
                  req {:secret (:secret config)
                       :port (:https-port config)
                       :disk_space cache-size
                       :tls_created_at (if-let [tls @tls-atom] (:time tls) "1970-01-01T00:00:00Z")
                       }]
              (log/info (str "POST " url))
              @(d/chain (http/post (-> config :api-server (uri/join "/ping") str)
                                   {:content-type :json
                                    :form-params req
                                    :as :json
                                    })
                        (fn [{{:keys [image_server tls]} :body}]
                          (when (and image_server (not= image_server @upstream-atom))
                            (log/info (str "image_server: " image_server))
                            (reset! upstream-atom image_server))
                          (when tls
                            (log/info (str "TLS Renew (created: " (:created_at tls) ")"))
                            (reset! tls-atom {:cert (:certificate tls)
                                              :key (:private_key tls)
                                              :time (:created_at tls)
                                              })))
                        ))
            (catch Exception ex
              (log/error ex))))))

    (shutdown/add-hook!
      :shutdown
      (fn []
        (log/info "Shutting down...")
        (shutdown-node)
        (.close cache)
        (.shutdownNow executor)
        ))
  ))

