(ns examples.sdl.audio.simple-playback
  "Port of SDL's examples/audio/01-simple-playback: create a simple audio stream
  and feed it a sine wave from the main loop, playing a constant 440Hz tone.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.render :as r]
            [examples.sdl.app :as app]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Audio Simple Playback" "1.0" "com.example.audio-simple-playback")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :audio)
    ;; we don't _need_ a window for audio-only things but it's good policy to have one.
    (let [[window renderer] (r/create-window-and-renderer "examples/audio/simple-playback" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; We're just playing a single thing here, so we'll use the simplified option.
      ;; We are always going to feed audio in as mono, float32 data at 8000Hz.
      ;; The stream will convert it to whatever the hardware wants on the other side.
      (let [stream (audio/open-device-stream :playback {:format :f32 :channels 1 :freq 8000})]
        ;; open-device-stream starts the device paused. You have to tell it to start!
        (audio/resume-stream-device! stream)
        (swap! state assoc :window window :renderer renderer :stream stream :current-sine-sample 0)
        :continue))))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer stream]} @state
        ;; 8000 float samples per second. Half of that.
        minimum-audio (quot (* 8000 4) 2)]
    ;; see if we need to feed the audio stream more data yet.
    ;; We're being lazy here, but if there's less than half a second queued, generate more.
    ;; A sine wave is unchanging audio--easy to stream--but for video games, you'll want
    ;; to generate significantly _less_ audio ahead of time!
    (when (< (audio/queued stream) minimum-audio)
      ;; this will feed 512 samples each frame until we get to our maximum.
      (let [samples (float-array 512)
            start (:current-sine-sample @state)]
        ;; generate a 440Hz pure tone
        (dotimes [i 512]
          (let [phase (/ (* (+ start i) 440) 8000.0)]
            (aset samples i (float (Math/sin (* phase 2 Math/PI))))))
        ;; wrapping around to avoid floating-point errors
        (swap! state assoc :current-sine-sample (mod (+ start 512) 8000))
        ;; feed the new data to the stream. It will queue at the end, and trickle out as
        ;; the hardware needs more data.
        (audio/put! stream samples)))
    ;; we're not doing anything with the renderer, so just blank it out.
    (r/clear! renderer)
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
