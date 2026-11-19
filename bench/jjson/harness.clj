(ns jjson.harness
  (:require [clojure.edn :as edn]))

(def corpus
  [{:name "apache_builds.json" :bytes 127275 :sha256 "f8e3422ac7d3c3550674afcb37e979e4e9bbeccffdb66933423495d55b6f5c74"}
   {:name "canada.json" :bytes 2251051 :sha256 "f83b3b354030d5dd58740c68ac4fecef64cb730a0d12a90362a7f23077f50d78"}
   {:name "citm_catalog.json" :bytes 1727204 :sha256 "a73e7a883f6ea8de113dff59702975e60119b4b58d451d518a929f31c92e2059"}
   {:name "github_events.json" :bytes 65132 :sha256 "c9eebb2cf2d46649059e9d48700919bacb3e8e0fb58452065a1a9de7778fd22e"}
   {:name "gsoc-2018.json" :bytes 3327831 :sha256 "72f1ef4898d88049da856c2ab8f4ec3e2c968ce209b2bbfd16cef842eb2e185f"}
   {:name "instruments.json" :bytes 220346 :sha256 "f3069235d4e2695d36c0c7735a435a7abb279fc4d64bbcf4ed9f888b8da1fdb9"}
   {:name "marine_ik.json" :bytes 2983466 :sha256 "61590a397542ae274ccd8a47ba9f64e7c9216a223a5e7e702dea5b2375411d62"}
   {:name "mesh.json" :bytes 723597 :sha256 "45bc8bf429340a874a7af8ea7056d60497402f80f55dba1e6ecc4ca8f1e46aff"}
   {:name "mesh.pretty.json" :bytes 1577353 :sha256 "b08b8b1881b131f82f8a6b8f8b0226235488154d7363cc8302153e724776ae8e"}
   {:name "numbers.json" :bytes 150124 :sha256 "82e9ddfe00963110ed8a0704e7df4d1ad1af9c0f336d1b24431ebc63cf430a2b"}
   {:name "random.json" :bytes 510476 :sha256 "61a3544f2bc987b7378c66a9025b1f23eb5456d4f0443595c06d6fc20f3b0a68"}
   {:name "twitter.json" :bytes 631515 :sha256 "549fce17ccd0ecc9605a12ea9adfbf3c92c7cce4fd6305e863ca710a4fabada5"}
   {:name "twitterescaped.json" :bytes 562408 :sha256 "2a288b5af4691c55b6f40fa534225b3e08b8d8b7f7ca4ed29bc5c7c81566ed4a"}
   {:name "update-center.json" :bytes 533178 :sha256 "e7b6a037bfdb5abdf122fa3445ddfacea78522a23df31c4799884c4ebf4c01fa"}])

(def corpus-bytes (reduce + (map :bytes corpus)))
(def warmup-ns 10000000000)
(def calibration-ns 2000000000)
(def sample-count 7)

(defn ensure! [value message data]
  (when-not value
    (throw (ex-info message data))))

(defn validate-collection-model! [decode library]
  (let [value (decode "{\"items\":[{\"n\":1}],\"empty-array\":[],\"empty-object\":{}}")]
    (ensure! (and (map? value)
                  (every? string? (keys value))
                  (vector? (get value "items"))
                  (map? (first (get value "items")))
                  (vector? (get value "empty-array"))
                  (map? (get value "empty-object")))
             "Decoder must materialize persistent maps and vectors with string keys"
             {:library library})))

(defn load-fixtures! [directory]
  (mapv (fn [{:keys [name bytes] :as fixture}]
          (let [text (slurp (str directory "/" name))
                actual-bytes (count (.getBytes text "UTF-8"))]
            (ensure! (= bytes actual-bytes)
                     "benchmark corpus byte count mismatch"
                     {:fixture name :expected bytes :actual actual-bytes})
            (assoc fixture :text text)))
        corpus))

(defn value-checksum [value]
  (cond
    (map? value) (count value)
    (sequential? value) (count value)
    (string? value) (count value)
    (nil? value) 0
    :else 1))

(defn string-checksum [value]
  (count value))

(defn progress! [path event]
  (when path
    (let [event (if (and (contains? event :completed)
                         (contains? event :total)
                         (pos? (:total event)))
                  (assoc event :percentage
                         (quot (* 100 (:completed event)) (:total event)))
                  event)]
      (spit path
            (str (pr-str (assoc event :timestamp-ms (System/currentTimeMillis)))
                 "\n")
            :append true))))

(defn consume-corpus [items operation checksum iterations]
  (loop [remaining iterations
         total 0]
    (if (zero? remaining)
      total
      (recur (dec remaining)
             (reduce (fn [sum item]
                       (+ sum (checksum (operation item))))
                     total
                     items)))))

(defn timed-corpus [items operation checksum iterations]
  (let [start (System/nanoTime)
        total (consume-corpus items operation checksum iterations)]
    {:iterations iterations
     :elapsed-ns (- (System/nanoTime) start)
     :checksum total}))

(defn warm-up! [items operation checksum]
  (let [deadline (+ (System/nanoTime) warmup-ns)]
    (loop [iterations 0
           total 0]
      (if (>= (System/nanoTime) deadline)
        {:iterations iterations :checksum total}
        (recur (inc iterations)
               (+ total (consume-corpus items operation checksum 1)))))))

(defn calibrate [items operation checksum]
  (loop [iterations 1]
    (let [sample (timed-corpus items operation checksum iterations)
          elapsed (:elapsed-ns sample)]
      (if (>= elapsed calibration-ns)
        iterations
        (let [scaled (long (+ 1 (* iterations
                                   (/ (double calibration-ns)
                                      (double (max 1 elapsed))))))]
          (recur (max (inc iterations) scaled)))))))

(defn throughput [bytes iterations elapsed-ns]
  (/ (* (double iterations) (double bytes) 1000000000.0)
     (* 1048576.0 (double elapsed-ns))))

(defn percentile [sorted-values proportion]
  (let [position (* proportion (double (dec (count sorted-values))))
        lower (long position)
        upper (min (inc lower) (dec (count sorted-values)))
        fraction (- position lower)]
    (+ (nth sorted-values lower)
       (* fraction (- (nth sorted-values upper) (nth sorted-values lower))))))

(defn benchmark! [{:keys [output runtime libraries command library operation
                          items run checksum progress case-index case-count]}]
  (progress! progress {:status :running
                       :phase :warmup
                       :case case-index
                       :cases case-count
                       :library library
                       :operation operation
                       :completed (* (dec case-index) sample-count)
                       :total (* case-count sample-count)})
  (println "warming" (name library) (name operation))
  (flush)
  (let [transfer-bytes (reduce + (map :bytes items))
        case-corpus (mapv #(select-keys % [:name :bytes :sha256]) items)
        warmup (warm-up! items run checksum)
        _ (progress! progress {:status :running
                              :phase :calibration
                              :case case-index
                              :cases case-count
                              :library library
                              :operation operation
                              :completed (* (dec case-index) sample-count)
                              :total (* case-count sample-count)})
        batch-size (calibrate items run checksum)
        samples (mapv (fn [sample-index]
                        (let [completed (+ (* (dec case-index) sample-count)
                                           sample-index)]
                          (progress! progress
                                     {:status :running
                                      :phase :sample
                                      :case case-index
                                      :cases case-count
                                      :library library
                                      :operation operation
                                      :sample (inc sample-index)
                                      :samples sample-count
                                      :completed completed
                                      :total (* case-count sample-count)})
                          (println "sample" (inc sample-index) "of" sample-count
                                   (name library) (name operation))
                          (flush)
                          (let [sample (timed-corpus items run checksum batch-size)]
                            (progress! progress
                                       {:status :running
                                        :phase :sample-complete
                                        :case case-index
                                        :cases case-count
                                        :library library
                                        :operation operation
                                        :sample (inc sample-index)
                                        :samples sample-count
                                        :completed (inc completed)
                                        :total (* case-count sample-count)})
                            (assoc sample :mib-per-second
                                   (throughput transfer-bytes
                                               (:iterations sample)
                                               (:elapsed-ns sample))))))
                      (range sample-count))
        sorted-throughputs (vec (sort (map :mib-per-second samples)))
        document {:schema 3
                  :measured-at-ms (System/currentTimeMillis)
                  :runtime runtime
                  :libraries libraries
                  :environment {:os (System/getProperty "os.name")
                                :os-version (System/getProperty "os.version")
                                :arch (System/getProperty "os.arch")}
                  :corpus case-corpus
                  :corpus-source "Boost.JSON 1.83.0 bench/data"
                  :corpus-bytes transfer-bytes
                  :command command
                  :execution :fresh-process-strictly-serial
                  :library library
                  :operation operation
                  :warmup-ns warmup-ns
                  :warmup-iterations (:iterations warmup)
                  :warmup-checksum (:checksum warmup)
                  :calibration-minimum-ns calibration-ns
                  :batch-size batch-size
                  :sample-count sample-count
                  :samples samples
                  :summary {:p25-mib-per-second (percentile sorted-throughputs 0.25)
                            :median-mib-per-second (percentile sorted-throughputs 0.50)
                            :p75-mib-per-second (percentile sorted-throughputs 0.75)}}]
    (spit output (str (pr-str document) "\n"))
    (println "wrote" output)
    document))

(defn consolidate! [output paths]
  (let [cases (mapv #(edn/read-string (slurp %)) paths)
        first-case (first cases)
        document {:schema 3
                  :runtime (:runtime first-case)
                  :libraries (:libraries first-case)
                  :environment (:environment first-case)
                  :corpus-source (:corpus-source first-case)
                  :execution :fresh-process-strictly-serial
                  :cases cases}]
    (spit output (str (pr-str document) "\n"))
    document))
