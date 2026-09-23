(ns sdl3.mixer-test
  "SDL_mixer, mostly through a device-less mixer whose output the tests read back.
  Skipped, with a note, when libSDL3_mixer is not installed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.core :as sdl]
            [sdl3.io :as io]
            [sdl3.mixer :as mx]
            [sdl3.test-util :as tu]))

(use-fixtures :once
  (fn [f]
    (if-not (mx/available?)
      (tu/skip-or-fail "sdl3.mixer-test" "libSDL3_mixer not installed")
      (do (sdl/set-hint! :audio-driver "dummy")
          (sdl/init! :audio)
          (try (mx/with-mixer-library (f))
               (finally (sdl/quit!) (sdl/reset-hint! :audio-driver)))))))

(def spec {:format :f32 :channels 2 :freq 48000})

(defn- peak [^floats pcm] (reduce max 0.0 (map #(Math/abs (double %)) pcm)))

(deftest library
  (is (= 3 (:major (mx/version))))
  (is (some #{"WAV"} (mx/decoders))))

(deftest offline-mixing
  (let [m (mx/create-mixer spec)
        tone (mx/sine-wave m 440 0.5 1000)
        t (mx/create-track m)]
    (try
      (is (= spec (mx/mixer-format m)))
      (is (= 1000 (mx/duration tone)))
      (is (= 48000 (mx/duration-frames tone)))
      (is (< (peak (mx/generate-floats m 960)) 1e-6) "silence before anything plays")
      (mx/set-track-audio! t tone)
      (mx/play! t)
      (is (mx/playing? t))
      (is (< 0.45 (peak (mx/generate-floats m 9600)) 0.55) "the tone at amplitude 0.5")
      (is (= 100 (mx/position t)) "4800 frames generated is 100ms")
      (is (= 900 (mx/remaining t)))
      (testing "gain scales the output"
        (mx/set-track-gain! t 0.5)
        (is (== 0.5 (mx/track-gain t)))
        (is (< 0.2 (peak (mx/generate-floats m 9600)) 0.3)))
      (mx/seek! t 500)
      (is (= 500 (mx/position t)))
      (mx/pause! t)
      (is (mx/paused? t))
      (is (< (peak (mx/generate-floats m 960)) 1e-6) "a paused track is silent")
      (mx/resume! t)
      (mx/stop! t)
      (mx/generate-floats m 96)
      (is (not (mx/playing? t)))
      (finally (mx/destroy-track! t) (mx/destroy-audio! tone) (mx/destroy-mixer! m)))))

(deftest placement
  (let [m (mx/create-mixer spec)
        t (mx/create-track m)]
    (try
      (mx/set-3d-position! t {:x 1 :y 0 :z -1})
      (is (= {:x 1.0 :y 0.0 :z -1.0} (mx/position-3d t)))
      (mx/set-3d-position! t nil)
      (mx/set-stereo! t {:left 1.0 :right 0.0})
      (mx/set-stereo! t nil)
      (finally (mx/destroy-track! t) (mx/destroy-mixer! m)))))

(deftest tags-and-callbacks
  (let [m (mx/create-mixer spec)
        tone (mx/sine-wave m 220 0.25 50)
        a (mx/create-track m)
        b (mx/create-track m)
        stopped (atom 0)]
    (try
      (doseq [t [a b]] (mx/set-track-audio! t tone) (mx/tag! t "sfx") (mx/on-stopped! t (fn [_] (swap! stopped inc))))
      (is (= ["sfx"] (mx/tags a)))
      (is (= 2 (count (mx/tagged-tracks m "sfx"))))
      (mx/play-tag! m "sfx" {:loops 1})
      (is (= 1 (mx/loops a)))
      (is (and (mx/playing? a) (mx/playing? b)))
      (mx/generate-floats m (* 2 48000))       ; 1s: past both passes of the 50ms tone
      (is (= 2 @stopped) "each track's stopped callback ran once")
      (mx/untag! b "sfx")
      (is (= 1 (count (mx/tagged-tracks m "sfx"))))
      (finally (mx/destroy-track! a) (mx/destroy-track! b) (mx/destroy-audio! tone) (mx/destroy-mixer! m)))))

(defn- wav-bytes [freq samples]
  (io/with-io [s (io/dynamic)]
    (io/write-bytes! s "RIFF") (io/write-num! s :u32-le (+ 36 (* 2 (count samples))))
    (io/write-bytes! s "WAVEfmt ") (io/write-num! s :u32-le 16)
    (io/write-num! s :u16-le 1) (io/write-num! s :u16-le 1)
    (io/write-num! s :u32-le freq) (io/write-num! s :u32-le (* 2 freq))
    (io/write-num! s :u16-le 2) (io/write-num! s :u16-le 16)
    (io/write-bytes! s "data") (io/write-num! s :u32-le (* 2 (count samples)))
    (doseq [v samples] (io/write-num! s :s16-le v))
    (io/contents s)))

(deftest loading
  (let [m (mx/create-mixer spec)]
    (try
      (let [wav (mx/load-audio-bytes m (wav-bytes 8000 (repeat 800 8000)) {:predecode true})]
        (is (= {:format :s16 :channels 1 :freq 8000} (mx/audio-format wav)))
        (is (= 100 (mx/duration wav)))
        (mx/destroy-audio! wav))
      (let [raw (mx/load-raw-audio m (float-array 480 0.25) {:format :f32 :channels 1 :freq 48000})]
        (is (= 10 (mx/duration raw)))
        (mx/destroy-audio! raw))
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"MIX_LoadAudio failed"
                            (mx/load-audio m "/nonexistent/jolt-sdl3.ogg")))
      (finally (mx/destroy-mixer! m)))))

(deftest device-mixer
  (let [m (mx/create-mixer-device)]
    (try
      (is (map? (mx/mixer-format m)))
      (let [tone (mx/sine-wave m 220 0.1 20)]
        (mx/play-audio! m tone)
        (mx/destroy-audio! tone))
      (finally (mx/destroy-mixer! m)))))

(deftest conversions
  (is (= 48000 (mx/ms->frames* 48000 1000)))
  (is (= 1000 (mx/frames->ms* 48000 48000))))
