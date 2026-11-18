(require '[clojure.java.io :as io]
         '[jjson.core :as jj])

;; A stuck parser must fail CI, not leave a permanently running job.
(future
  (Thread/sleep 180000)
  (binding [*out* *err*] (println "FAIL: JSONTestSuite timed out after 180 seconds"))
  (System/exit 124))

(doseq [[mode options] [[:default nil] [:exact {:bigints true :bigdecimals true}]]]
(let [directory (first *command-line-args*)
      files (sort-by #(.getName %) (filter #(.isFile %) (.listFiles (io/file directory))))
      counts (atom {:y-accepted 0 :y-rejected 0 :n-accepted 0 :n-rejected 0
                    :i-accepted 0 :i-rejected 0})
      failures (atom [])]
  (when-not (seq files)
    (throw (ex-info "JSONTestSuite corpus is empty" {:directory directory})))
  (doseq [file files]
    (let [name (.getName file)
          category (subs name 0 1)
          input (java.nio.file.Files/readAllBytes (.toPath file))
          accepted? (try
                      (jj/from-json input options)
                      true
                      (catch Exception e
                        (if (= :jjson/error (:type (ex-data e)))
                          false
                          (throw e))))]
      (when-not (#{"y" "n" "i"} category)
        (throw (ex-info "Unknown conformance case category" {:file name})))
      (swap! counts update (keyword (str category (if accepted? "-accepted" "-rejected"))) inc)
      (when (or (and (= category "y") (not accepted?))
                (and (= category "n") accepted?))
        (swap! failures conj name)
        (println "FAIL:" name (if accepted? "accepted" "rejected")))
      (when (= category "i")
        (println "implementation-defined:" name (if accepted? "accepted" "rejected")))
      (flush)))
  (prn {:mode mode :outcomes @counts})
  (when (seq @failures)
    (throw (ex-info "JSONTestSuite required outcomes failed" {:files @failures})))
  (println "PASS:" mode (count files) "fixtures; no required-outcome failures")
  (flush)))
(System/exit 0)
