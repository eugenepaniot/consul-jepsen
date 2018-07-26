(ns css.consul
  (:require [clojure.tools.logging :refer [debug info warn]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jepsen.core :as core]
            [jepsen.util :refer [meh timeout]]
            [jepsen.core :as core]
            [jepsen.control :as c]
            [jepsen.control.net :as net]
            [jepsen.control.util :as cu]
            [jepsen.client :as client]
            [jepsen.db :as db]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [base64-clj.core :as base64]
            [slingshot.slingshot :refer [try+]]
            [knossos.model :refer [inconsistent]]
            )
  (:import (java.net SocketTimeoutException)))

(def data-dir "/var/lib/consul-server")
(def consul-token "acl_master_token")
(def consistent "consistent")

(defn flush-iptables-rules!
  [test node]
  (info node "flusgin iptables")
  (c/exec :iptables :-F :-v ))


(defn start-consul-server!
  [test node]
  (info node "starting consul")
  (c/exec :systemctl :start :consul-server))


(defn start-consul-client!
  [test node]
  (info node "starting consul")
  (c/exec :systemctl :start :consul-client))


(defn db []
  (reify db/DB
    (setup! [this test node]
      (flush-iptables-rules! test node)
      (start-consul-server! test node)
      (start-consul-client! test node)
      (Thread/sleep 1000)
      (info node "consul ready"))

    (teardown! [_ test node]
;      (c/su
;        (meh (c/exec :systemctl :stop :consul-server)))
      (flush-iptables-rules! test node)
      (info node "consul nuked")
     )))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn maybe-int [value]
  (if (= value "null")
      nil
      (Integer. value)))

(defn parse-index [resp]
  (-> resp
      :headers
      (get "x-consul-index")
      Integer.)
)

(defn parse-effective-consistency [resp]
  (-> resp
      :headers
      (get "x-consul-effective-consistency")
      String.)
  )

(defn parse-body
  "Parse the base64 encoded value.
   The response JSON looks like:
    [
     {
       \"CreateIndex\": 100,
       \"ModifyIndex\": 200,
       \"Key\": \"foo\",
       \"Flags\": 0,
       \"Value\": \"YmFy\"
     }
    ]
  "
  [resp]
  (let [body  (-> resp
                  :body
                  (json/parse-string #(keyword (.toLowerCase %)))
                  first)
        value (-> body :value base64/decode maybe-int)]
    (assoc body :value value)))

(defn parse [response]
  (assoc (parse-body response)
         :index (parse-index response)
         ))

(defn consul-get [key-url & {:keys [params] :or {params {}}}]
  (http/get key-url (merge params
                           {
                             :headers {"X-Consul-Token" consul-token }
                             })
  )
)

(defn consul-put! [key-url value & {:keys [params] :or {params {}}}]
  (http/put key-url  (merge params
                            {
                              :body value
                              :headers {"X-Consul-Token" consul-token }
                            }))
)

(defn consul-cas! [key-url old-value new-value]
  "Consul uses an index based CAS so we must first get the existing value for
   this key and then use the index for a CAS!"
  (let [resp (parse (consul-get key-url))
        index (:index resp)
        existing-value (:value resp)]
    (if (= existing-value old-value)
      (let [
        body (:body (consul-put! key-url new-value :params { :query-params {:cas index} })) ]
        (= body "true"))
      (throw (ex-info "cas-false"
                      { :existing-value existing-value :old-value old-value }))
    )
  )
)


(defrecord CASClient [k client]
  client/Client
  (setup! [this test node]
    (let [client (str "http://" (name node) ":18500/v1/kv/" k )]
      (consul-put! client (json/generate-string nil) )
      (assoc this :client client)))

  (invoke! [this test op]
    (case (:f op)

      :read (try+
             (
                let [resp  (parse (consul-get client
                                              :params
                                              { :query-params { "consistent" true }} ))]
                  (assoc op :type :ok :value (:value resp))
               )
              (catch SocketTimeoutException e
                (assoc op :type :fail, :error :timeout))

                (catch Exception e
                  (assoc op :type :fail, :error (.getMessage e) ))
             )

      :write (try+ (
                do (->> (:value op)
                        json/generate-string
                        (consul-put! client)
                  )
                  (assoc op :type :ok)
              )
              (catch SocketTimeoutException e
                (assoc op :type :fail, :error :timeout))

              (catch Exception e
                (assoc op :type :fail, :error (.getMessage e) ))
            )

      :cas (try+
             (
               let [[old new] (:value op)
                ok?     (consul-cas! client
                                     (json/generate-string old)
                                     (json/generate-string new)
                                     )]
                (assoc op :type (if ok? :ok (:fail ) )))

              (catch SocketTimeoutException e
                (assoc op :type :fail, :error :timeout))

              (catch (and (instance? clojure.lang.ExceptionInfo %)) e
                (let [msg (.getMessage e)]
                  (assoc op :type :fail :error :msg )))

              (catch Exception e
                (assoc op :type :fail, :error (.getMessage e) ))
            )
    )
  )

  (teardown! [_ test]
    (info "Teardown tests")
  )
)

(defn cas-client
  "A compare and set register built around a single consul node."
  []
  (CASClient. "jepsen" nil))
