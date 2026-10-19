(ns jjson.build-smoke
  (:require [jjson.core :as json]))

(defn -main [& _]
  (let [source "{\"user\":{\"name\":\"Ada\",\"active\":true},\"n\":[1,-2,3.5,null]}"
        expected {"user" {"name" "Ada" "active" true}
                  "n" [1 -2 3.5 nil]}
        bytes (.getBytes source "UTF-8")]
    (when-not (= expected (json/from-json bytes))
      (throw (ex-info "build-smoke: byte parse mismatch" {:expected expected})))
    (when-not (= expected (json/from-json (json/to-json expected)))
      (throw (ex-info "build-smoke: compact round trip mismatch" {})))
    (when-not (= expected (json/from-json (json/to-json expected {:pretty true})))
      (throw (ex-info "build-smoke: pretty round trip mismatch" {})))
    (println "ok")))