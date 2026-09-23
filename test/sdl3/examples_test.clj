(ns sdl3.examples-test
  "Every example must at least load: CI cannot watch their windows, but a
  namespace that fails to compile, or calls a function that no longer exists,
  fails here."
  (:require [clojure.test :refer [deftest is testing]]
            [examples.sdl.run :as run]
            [examples.showcase.run :as showcase]))

(defn- example-files [dir]
  (->> (file-seq (java.io.File. ^String dir))
       (map str)
       (filter #(.endsWith ^String % ".clj"))
       (remove #(re-find #"/(app|assets|run)\.clj$" %))
       count))

(deftest every-example-loads
  (doseq [[id ns _] run/examples]
    (testing id
      (is (nil? (require ns)))
      (is (some? (ns-resolve ns '-main)) (str id " has a -main"))))
  (doseq [[name ns _] showcase/examples]
    (testing name
      (is (nil? (require ns)))
      (is (some? (ns-resolve ns '-main))))))

(deftest registries-are-complete
  (is (= (example-files "examples/examples/sdl") (count run/examples))
      "every port under examples/examples/sdl is in its runner's registry")
  (is (= (example-files "examples/examples/showcase") (count showcase/examples))
      "every example under examples/examples/showcase is in its runner's registry"))
