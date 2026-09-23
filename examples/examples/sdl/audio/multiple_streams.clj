(ns examples.sdl.audio.multiple-streams
  "Port of SDL's examples/audio/04-multiple-streams: load two .wav files, put them
  in audio streams and bind them for playback, repeating both sounds on loop. This
  shows several streams mixing into a single playback device.

  sample.wav is from Will Provost's The Living Proof, used with permission;
  sword.wav is sword04.wav by Erdie (https://freesound.org/s/27858/, CC BY 4.0).
  Both are fetched by examples.sdl.assets.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.render :as r]
            [sdl3.log :as log]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(defn- init-sound
  "Load a .wav and bind a stream for it to `audio-device`: {:wav-data :stream}, or
  nil after logging why."
  [audio-device fname]
  (let [{:keys [spec data]} (try (audio/load-wav (assets/path fname))
                                 (catch clojure.lang.ExceptionInfo e
                                   (log/log! "Couldn't load .wav file: " (:sdl/error (ex-data e) (ex-message e)))
                                   nil))]
    (when data
      ;; Create an audio stream. Set the source format to the wav's format (what
      ;; we'll input), leave the dest format nil here (it'll change to what the
      ;; device wants once we bind it).
      (let [stream (try (audio/create-stream spec nil)
                        (catch clojure.lang.ExceptionInfo e
                          (log/log! "Couldn't create audio stream: " (:sdl/error (ex-data e)))
                          nil))]
        (when stream
          ;; once bound, it'll start playing when there is data available!
          (try (audio/bind! audio-device stream)
               {:wav-data data :stream stream}
               (catch clojure.lang.ExceptionInfo e
                 (log/log! "Failed to bind '" fname "' stream to device: " (:sdl/error (ex-data e)))
                 (audio/destroy-stream! stream)
                 nil)))))))

(defn init [state _]
  (sdl/set-app-metadata! "Example Audio Multiple Streams" "1.0" "com.example.audio-multiple-streams")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :audio)
    (let [[window renderer] (r/create-window-and-renderer "examples/audio/multiple-streams" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; open the default audio device in whatever format it prefers; our audio streams
      ;; will adjust to it.
      (let [audio-device (audio/open-device :playback)]
        (swap! state assoc :window window :renderer renderer :audio-device audio-device :sounds [])
        ;; each sound joins the state as it loads, so quit cleans up whatever did load
        (if (every? (fn [fname]
                      (when-let [s (init-sound audio-device fname)]
                        (swap! state update :sounds conj s)
                        true))
                    ["sample.wav" "sword.wav"])
          :continue
          :failure)))))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer sounds]} @state]
    (doseq [{:keys [stream ^bytes wav-data]} sounds]
      ;; If less than a full copy of the audio is queued for playback, put another copy
      ;; in there. This is overkill, but easy when lots of RAM is cheap. One could be
      ;; more careful and queue less at a time, as long as the stream doesn't run dry.
      (when (< (audio/queued stream) (alength wav-data))
        (audio/put! stream wav-data)))
    ;; just blank the screen.
    (r/set-draw-color! renderer 0 0 0 255)
    (r/clear! renderer)
    (r/present! renderer)
    :continue))

(defn quit [state _]
  (let [{:keys [audio-device sounds]} @state]
    (when audio-device (audio/close-device! audio-device))
    (doseq [{:keys [stream]} sounds]
      (audio/destroy-stream! stream))))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
