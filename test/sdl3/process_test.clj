(ns sdl3.process-test
  (:require [clojure.test :refer [deftest is]]
            [sdl3.io :as io]
            [sdl3.process :as pr]))

(def ^:private posix? (not (re-find #"(?i)windows" (or (System/getProperty "os.name") ""))))

(deftest sh-captures-output
  (when posix?
    (is (= {:exit 0 :out "hello\n"} (pr/sh "echo" "hello")))
    (is (= 3 (:exit (pr/sh "sh" "-c" "exit 3"))))
    (is (= "bar /tmp\n" (-> (pr/sh "sh" "-c" "echo $FOO $(cd /tmp && pwd -L)" {:env {"FOO" "bar" "PATH" "/bin:/usr/bin"}}) :out)))))

(deftest pipes-both-ways
  (when posix?
    (let [p (pr/create-process ["sort"] {:stdin :app :stdout :app})]
      (try
        (io/write-bytes! (pr/input p) "b\na\nc\n")
        (io/close! (pr/input p))
        (let [{:keys [exit out-bytes]} (pr/read-output! p)]
          (is (= 0 exit))
          (is (= "a\nb\nc\n" (String. ^bytes out-bytes "UTF-8"))))
        (finally (pr/destroy! p))))))

(deftest wait-and-kill
  (when posix?
    (let [p (pr/create-process ["sleep" "10"])]
      (try
        (is (pos? (pr/pid p)))
        (is (nil? (pr/wait! p false)) "still running")
        (pr/kill! p)
        (is (some? (pr/wait! p)))
        (finally (pr/destroy! p))))))

(deftest missing-program-raises
  (is (thrown? clojure.lang.ExceptionInfo (pr/sh "no-such-program-jolt-sdl3"))))
