(ns mangadex.client.core
  (:gen-class)
  (:require [clojure.pprint :refer [pprint]]
            [clojure.java.io :as io]
            [clojure.edn :as edn]
            [manifold.time :refer [every minutes]]
            [manifold.stream :refer [->source stream connect-via]]
            [manifold.deferred :refer [chain]]
            [aleph.http :as http]
            [aleph.http.client-middleware :refer [default-middleware]]
            [aleph.netty :as netty]
            [compojure.core :refer [defroutes GET]]
            [lambdaisland.uri :as uri]
            [buddy.core.keys.pem :as pem]
            [buddy.core.hash :refer [md5]]
            [shutdown.core :as shutdown]
            [taoensso.timbre :as log]
            )
  (:import [java.util.concurrent ScheduledThreadPoolExecutor]
           [javax.crypto Cipher CipherInputStream CipherOutputStream]
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
        cache (DiskLruCache/open (io/file "data") 0 1 cache-size)
        ]

    (defroutes http-handler
      (GET "/data/:chapter/:image" [chapter image :as req]
           (log/info (str "GET " (:uri req)))
           (let [cache-key (md5 (str chapter image))
                 rc4 (doto (Cipher/getInstance "RC4")
                       (.init Cipher/ENCRYPT_MODE cache-key))]
             (if-let [entry (.get cache cache-key)]
               {:body (-> entry
                          (.getInputStream 0)
                          (CipherInputStream. rc4)
                          ->source)}
               (let [upstream-url (str (uri/join @upstream-atom "data" chapter image))
                     cache-stream (-> cache
                                      (.edit cache-key)
                                      (.newOutputStream 0)
                                      (CipherOutputStream. rc4))
                     out-stream (stream)]
                 (chain (http/get upstream-url)
                        (fn [{:keys [body]}]
                          (connect-via body (fn [chk] (.write cache-stream chk)) out-stream)
                          {:body out-stream}
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
            (println (-> tls :key .getBytes (pem/read-privkey "")
                                    .getEncoded (String. "UTF-8")))
            (let [ssl-ctx (-> (SslContextBuilder/forServer
                                (-> tls :key .getBytes (pem/read-privkey ""))
                                (-> tls :cert .getBytes io/input-stream)
                                )
                              (.build))
                  server (http/start-server
                           http-handler
                           {:port (:https-port config)
                            :ssl-context ssl-ctx
                            :pipeline-transform 
                            (fn [pipeline]
                              (.addLast pipeline bandwidth-limiter egress-limiter))
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
          @(chain (http/post (-> config :api-server (uri/join "/ping") str)
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
        (.shutdown executor)
        ))
    ))

