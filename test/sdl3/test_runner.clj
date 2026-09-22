(ns sdl3.test-runner
  "Runs every sdl3.*-test namespace headlessly (no window is opened), exiting
  non-zero on any failure. `jolt -M:test` or `jolt test`."
  (:require [clojure.test :as t]
            [sdl3.abi-test]
            [sdl3.asyncio-test]
            [sdl3.audio-test]
            [sdl3.core-test]
            [sdl3.devices-test]
            [sdl3.events-test]
            [sdl3.gpu-test]
            [sdl3.io-test]
            [sdl3.joystick-test]
            [sdl3.process-test]
            [sdl3.properties-test]
            [sdl3.rect-test]
            [sdl3.storage-test]
            [sdl3.system-test]
            [sdl3.thread-test]
            [sdl3.timer-test]
            [sdl3.tray-dialog-test]))

(defn -main [& _]
  (let [{:keys [fail error]} (t/run-all-tests #"sdl3\..*-test")]
    (System/exit (if (zero? (+ fail error)) 0 1))))
