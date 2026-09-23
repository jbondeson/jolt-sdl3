(ns examples.sdl.audio.load-wav
  "Port of SDL's examples/audio/03-load-wav: load a .wav file, put it in an audio
  stream and play it on a loop.

  The .wav file is a sample from Will Provost's song, The Living Proof, used with
  permission (see examples.sdl.assets for how it is fetched):

     From the album The Living Proof
     Publisher: 5 Guys Named Will
     Copyright 1996 Will Provost
     https://itunes.apple.com/us/album/the-living-proof/id4153978
     http://www.amazon.com/The-Living-Proof-Will-Provost/dp/B00004R8RH

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.render :as r]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Audio Load Wave" "1.0" "com.example.audio-load-wav")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :audio)
    ;; we don't _need_ a window for audio-only things but it's good policy to have one.
    (let [[window renderer] (r/create-window-and-renderer "examples/audio/load-wav" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; Load the .wav file (the C example loads it from wherever the app is run from).
      (let [{:keys [spec data]} (audio/load-wav (assets/path "sample.wav"))
            ;; Create our audio stream in the same format as the .wav file. It'll
            ;; convert to what the audio hardware wants.
            stream (audio/open-device-stream :playback spec)]
        ;; open-device-stream starts the device paused. You have to tell it to start!
        (audio/resume-stream-device! stream)
        (swap! state assoc :window window :renderer renderer :stream stream :wav-data data)
        :continue))))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer stream ^bytes wav-data]} @state]
    ;; see if we need to feed the audio stream more data yet.
    ;; We're being lazy here, but if there's less than the entire wav file left to play,
    ;; just shove a whole copy of it into the queue, so we always have _tons_ of
    ;; data queued for playback.
    (when (< (audio/queued stream) (alength wav-data))
      ;; feed more data to the stream. It will queue at the end, and trickle out as the
      ;; hardware needs more data.
      (audio/put! stream wav-data))
    ;; we're not doing anything with the renderer, so just blank it out.
    (r/clear! renderer)
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
