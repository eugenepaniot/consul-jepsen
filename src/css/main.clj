(ns css.main
 (:gen-class)
 (:require 
	[clojure.tools.logging :refer :all]
	[clojure.string :as str]
	[slingshot.slingshot :refer [try+]]
	[knossos.model :as model]
	[jepsen [checker :as checker]
		[cli :as cli]
		[client :as client]
		[control :as c]
		[db :as db]
		[generator :as gen]
		[nemesis :as nemesis]
		[tests :as tests]
    [independent :as independent]
		[util :as util :refer [timeout]]]
	[jepsen.checker.timeline :as timeline]
	[jepsen.control.util :as cu]
	[jepsen.os.debian :as debian]
	[css.consul :as cssc]
 )
)


;Jepsen is a framework for distributed systems verification, with fault injection, written by Kyle Kingsbury.
;It fuzzes the system with random operations while injecting network partitions.
;The results of operation history is analyzed to see if the system violates any of the consistency properties it claims to have.
;It generates graphs of performance and availability, helping user characterize how a system responds to different faults.


(defn css-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge tests/noop-test
	{
		:name      "consul"
    :client    (cssc/cas-client)
    :db        (cssc/db)
    :model     (model/cas-register nil)
    :checker   (checker/compose {
        :perf   (checker/perf)
        :html   (timeline/html)
        :linear (checker/linearizable)
      }
    )
    :nemesis   (nemesis/partition-random-halves)
    :generator (gen/phases
                  (->> gen/cas
                    (gen/nemesis
                      (gen/seq
                        (cycle [(gen/sleep 60)
                          {:type :info :f :start}
                          (gen/sleep 60)
                          {:type :info :f :stop}]
                        )
                      )
                    )
                    (gen/time-limit 600))

                  (gen/nemesis
                    (gen/once {:type :info :f :stop}))

                  (gen/sleep 30)

                  (gen/clients
                    (gen/once {:type :invoke :f :read}))
              )
	}
  opts)
)

(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (merge (cli/single-test-cmd {:test-fn css-test})
                   (cli/serve-cmd))
            args))
