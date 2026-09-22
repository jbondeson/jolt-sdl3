(ns examples.tone
  "Audio streams: play a two-second 440 Hz tone, generated in a stream callback
  on SDL's audio thread. `jolt tone` runs it."
  (:require [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.timer :as timer]))

(defn -main [& _]
  (sdl/with-sdl [:audio]
    (let [freq 48000
          phase (atom 0)
          s (audio/open-device-stream
             :playback {:format :f32 :channels 1 :freq freq}
             (fn [stream additional _]
               (let [n (quot additional 4)
                     start @phase
                     samples (float-array n)]
                 (dotimes [i n]
                   (aset samples i (float (* 0.2 (Math/sin (/ (* 2 Math/PI 440 (+ start i)) freq))))))
                 (swap! phase + n)
                 (audio/put! stream samples))))]
      (println "playing on" (audio/current-driver))
      (audio/resume-stream-device! s)
      (timer/delay! 2000)
      (audio/destroy-stream! s))))
