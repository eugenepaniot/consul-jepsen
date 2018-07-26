(defproject css "0.1.0-SNAPSHOT"
  :description "A Jepsen test for CSS - Consul 1.4.0"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :main css.main
  :dependencies [
	  [org.clojure/clojure "1.8.0"]
	  [jepsen "0.1.6"]
	  [cheshire "5.8.0"]
    [clj-http "1.0.1"]
	  [base64-clj "0.1.1"]
    [potemkin "0.4.4"]
  ]
)
