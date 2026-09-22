(ns sdl3.test-runner
  "Runs every sdl3.*-test namespace headlessly (no window is opened), exiting
  non-zero on any failure. `jolt -M:test` or `jolt test`."
  (:require [clojure.test :as t]
            [sdl3.abi-test]
            [sdl3.audio-test]
            [sdl3.core-test]
            [sdl3.events-test]
            [sdl3.gpu-test]
            [sdl3.io-test]
            [sdl3.joystick-test]
            [sdl3.properties-test]
            [sdl3.rect-test]
            [sdl3.timer-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-all-tests #"sdl3\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
