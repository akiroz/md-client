(ns mangadex.client.core
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.string :refer [join lower-case]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [byte-streams :refer [convert stream-of]]
            [manifold.time :refer [every minutes]]
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
            [taoensso.nippy :as nippy]
            )
  (:import [java.util Base64]
           [java.util.concurrent ScheduledThreadPoolExecutor]
           [javax.xml.bind DatatypeConverter]
           [javax.crypto Cipher CipherInputStream CipherOutputStream]
           [javax.crypto.spec SecretKeySpec]
           [io.netty.handler.ssl SslContextBuilder]
           [io.netty.handler.traffic GlobalTrafficShapingHandler]
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

  (println "=== Starting Mangadex@Home Client ==============================")
  (let [config (edn/read-string (slurp "config.edn"))
        _ (pprint config) ;; Print config for debugging
        _ (println "================================================================")
        executor (ScheduledThreadPoolExecutor. 1)
        burst-limit (-> config :burst-rate (* 1024))
        egress-limit (-> config :egress-rate (* 17476))
        bandwidth-limiter (GlobalTrafficShapingHandler. executor burst-limit 0 1000 10000)
        egress-limiter (GlobalTrafficShapingHandler. executor egress-limit 0 60000 1)
        cache-size (-> config :cache-size (* 1024 1024))
        cache (DiskLruCache/open (io/file "data") 0 2 cache-size)
        ]

    (defroutes http-handler
      (GET "/data/:chapter/:image" [chapter image :as req]
           (log/info (str "GET " (:uri req)))
           (let [cache-key (md5 (str chapter image))
                 cache-key-str (lower-case (DatatypeConverter/printHexBinary cache-key))
                 rc4 (get-cipher "RC4" cache-key)]
             (if-let [entry (.get cache cache-key-str)]
               {:body (-> entry (.getInputStream 0) (CipherInputStream. rc4))
                :headers (-> entry (.getString 1) decode64 nippy/thaw)}
               (do (log/info (str "Cache miss, fetching from " @upstream-atom))
                   (d/chain (http/get (str (uri/join @upstream-atom (str "/data/" chapter "/" image))))
                          (fn [{:keys [body headers]}]
                            (if-let [content-length (-> headers
                                                        (get "content-length")
                                                        edn/read-string)]
                              (let [cache-entry (-> cache (.edit cache-key-str))
                                    stored-headers (select-keys headers ["content-length"
                                                                         "content-type"
                                                                         "etag"])
                                    counter (atom 0)
                                    cache-stream (-> cache-entry
                                                     (.newOutputStream 0)
                                                     (CipherOutputStream. rc4))
                                    in-stream (convert body (stream-of bytes) {:chunk-size 4096})
                                    out-stream (buffered-stream alength content-length)]
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
                                 :headers stored-headers})
                              ;; Passthrough request if no content-length
                              {:body body
                               :headers headers})
                            )))
               ))))

    (add-watch
      tls-atom :tls-update
      (fn [_ _ _ tls]
        (try
          (log/info "(Re)Starting server...")
          (when tls
            (when-let [server @server-atom]
              (reset! server-atom nil)
              (netty/close server)
              (netty/wait-for-close server))
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
                            :pipeline-transform 
                            (fn [pipeline]
                              (.addLast pipeline bandwidth-limiter)
                              (.addLast pipeline egress-limiter))
                            })]
              (log/info "Server started")
              (reset! server-atom server)))
          (catch Exception ex
            (log/error ex)))))

    (every
      (minutes 1)
      (fn []
        (try
          (let [url (-> config :api-server (uri/join "/ping") str)
                req {:secret (:secret config)
                     :port (:https-port config)
                     :tls_created_at (if-let [tls @tls-atom] (:time tls) "1970-01-01T00:00:00Z")
                     :requested_shard_count (:shard-count config)
                     }]
            (log/info (str "POST " url))
            @(d/chain (http/post (-> config :api-server (uri/join "/ping") str)
                               {:content-type :json
                                :form-params req
                                :as :json
                                })
                    (fn [{{:keys [image_server tls]} :body}]
                      (log/info "Ping success")
                      (when image_server
                        (log/info (str "  > got image_server " image_server))
                        (reset! upstream-atom image_server))
                      (when tls
                        (log/info (str "  > got tls - created: " (:created_at tls)))
                        (reset! tls-atom {:cert (:certificate tls)
                                          :key (:private_key tls)
                                          :time (:created_at tls)
                                          })))
                    ))
          (catch Exception ex
            (log/error ex))
          )))

    (shutdown/add-hook!
      :shutdown
      (fn []
        (log/info "Shutting down Mangadex@Home client...")
        @(http/post (-> config :api-server (uri/join "/stop") str)
                    {:content-type :json 
                     :form-params {:secret (:secret config)}})
        (when-let [server @server-atom]
          (reset! server-atom nil)
          (netty/close server)
          (netty/wait-for-close server))
        (.close cache)
        (.shutdown executor)
        ))
    ))

