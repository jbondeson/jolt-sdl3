(ns examples.sdl.audio.simple-playback-callback
  "Port of SDL's examples/audio/02-simple-playback-callback: create a simple
  audio stream and feed it a sine wave from a callback that SDL calls when the
  stream wants more data, playing a constant 440Hz tone.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.render :as r]
            [examples.sdl.app :as app]))

;; the callback runs on SDL's audio thread, so its position in the wave lives in
;; an atom of its own rather than in the app state
(def ^:private current-sine-sample (atom 0))

(defn- feed-the-audio-stream-more
  "Called (usually in a background thread) when the audio stream is consuming data."
  [astream additional-amount _total-amount]
  ;; total-amount is how much data the audio stream is eating right now,
  ;; additional-amount is how much more it needs than what it currently has queued
  ;; (which might be zero!). You can supply any amount of data here; it will take
  ;; what it needs and use the extra later. If you don't give it enough, it will
  ;; take everything and then feed silence to the hardware for the rest. Ideally,
  ;; though, we always give it what it needs and no extra, so we aren't buffering
  ;; more than necessary.
  (loop [remaining (quot additional-amount 4)]       ; convert from bytes to samples
    (when (pos? remaining)
      ;; this will feed 128 samples each iteration until we have enough.
      (let [total (min remaining 128)
            samples (float-array total)
            start @current-sine-sample]
        ;; generate a 440Hz pure tone
        (dotimes [i total]
          (let [phase (/ (* (+ start i) 440) 8000.0)]
            (aset samples i (float (Math/sin (* phase 2 Math/PI))))))
        ;; wrapping around to avoid floating-point errors
        (reset! current-sine-sample (mod (+ start total) 8000))
        ;; feed the new data to the stream. It will queue at the end, and trickle out
        ;; as the hardware needs more data.
        (audio/put! astream samples)
        (recur (- remaining total))))))                ; subtract what we've just fed the stream.

(defn init [state _]
  (sdl/set-app-metadata! "Example Simple Audio Playback Callback" "1.0" "com.example.audio-simple-playback-callback")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :audio)
    ;; we don't _need_ a window for audio-only things but it's good policy to have one.
    (let [[window renderer] (r/create-window-and-renderer "examples/audio/simple-playback-callback" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; We're just playing a single thing here, so we'll use the simplified option.
      ;; We are always going to feed audio in as mono, float32 data at 8000Hz.
      ;; The stream will convert it to whatever the hardware wants on the other side.
      (let [stream (audio/open-device-stream :playback {:format :f32 :channels 1 :freq 8000}
                                             feed-the-audio-stream-more)]
        ;; open-device-stream starts the device paused. You have to tell it to start!
        (audio/resume-stream-device! stream)
        (swap! state assoc :window window :renderer renderer :stream stream)
        :continue))))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer]} @state]
    ;; we're not doing anything with the renderer, so just blank it out.
    (r/clear! renderer)
    (r/present! renderer)
    ;; all the work of feeding the audio stream is happening in a callback in a
    ;; background thread.
    :continue))

(defn quit [state _]
  ;; destroying the stream also releases its callback
  (some-> (:stream @state) audio/destroy-stream!))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
