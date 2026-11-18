(ns jjson.build-smoke
  (:require [jjson.core :as jj]))

(defn -main [& _]
  (let [source "{\"user\":{\"name\":\"Ada\",\"active\":true},\"n\":[1,-2,3.5,null]}"
        expected {"user" {"name" "Ada" "active" true}
                  "n" [1 -2 3.5 nil]}
        bytes (.getBytes source "UTF-8")]
    (when-not (= expected (jj/from-json bytes))
      (throw (ex-info "build-smoke: byte parse mismatch" {:expected expected})))
    (when-not (= expected (jj/from-json (jj/to-json expected)))
      (throw (ex-info "build-smoke: compact round trip mismatch" {})))
    (when-not (= expected (jj/from-json (jj/to-json expected {:pretty true})))
      (throw (ex-info "build-smoke: pretty round trip mismatch" {})))
    (when-not (= expected (jj/from-json (jj/to-json-bytes expected)))
      (throw (ex-info "build-smoke: direct byte round trip mismatch" {})))
    (when-not (= 184467440737095516161234567890N
                 (jj/from-json "184467440737095516161234567890" {:bigints true}))
      (throw (ex-info "build-smoke: exact integer mismatch" {})))
    (when-not (= 0.123456789012345678901234567890M
                 (jj/from-json "0.123456789012345678901234567890" {:bigdecimals true}))
      (throw (ex-info "build-smoke: exact decimal mismatch" {})))
    (when-not (= :duplicate-key
                 (try
                   (jj/from-json "{\"x\":1,\"x\":2}" {:reject-duplicate-keys true})
                   :accepted
                   (catch Exception e (:reason (ex-data e)))))
      (throw (ex-info "build-smoke: duplicate key accepted" {})))
    (when-not (= 5
                 (try
                   (jj/from-json "{\"x\":}")
                   :accepted
                   (catch Exception e (:byte-offset (ex-data e)))))
      (throw (ex-info "build-smoke: error location mismatch" {})))
    (println "ok")))