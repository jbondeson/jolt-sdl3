(ns sdl3.audio-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.core :as sdl]
            [sdl3.audio :as au]
            [sdl3.io :as io]
            [sdl3.timer :as timer]))

;; the dummy driver needs no hardware and still runs a device thread
(use-fixtures :each (fn [f]
                      (sdl/set-hint! :audio-driver "dummy")
                      (sdl/init! :audio)
                      (try (f) (finally (sdl/quit!) (sdl/reset-hint! :audio-driver)))))

(deftest formats
  (is (= 4 (au/bytes-per-sample :f32)))
  (is (= 4 (au/frame-size {:format :s16 :channels 2})))
  (is (= 128 (au/silence-value :u8)))
  (is (= "SDL_AUDIO_S16LE" (au/format-name :s16le))))

(deftest devices
  (is (= "dummy" (au/current-driver)))
  (is (seq (au/playback-devices)))
  (is (string? (au/device-name :playback)))
  (let [d (au/open-device :playback {:format :f32 :channels 2 :freq 48000})]
    (is (pos? d))
    (au/pause-device! d)
    (is (au/device-paused? d))
    (au/resume-device! d)
    (is (not (au/device-paused? d)))
    (au/set-device-gain! d 0.5)
    (is (== 0.5 (au/device-gain d)))
    (au/close-device! d)))

(deftest stream-conversion
  (let [s (au/create-stream {:format :s16 :channels 1 :freq 8000} {:format :f32 :channels 1 :freq 8000})]
    (try
      (au/put! s (short-array [0 16384 -32768]))
      (au/flush! s)
      (is (= [0.0 0.5 -1.0] (vec (au/get-floats s))))
      (is (= {:src {:format :s16 :channels 1 :freq 8000} :dst {:format :f32 :channels 1 :freq 8000}}
             (au/stream-format s)))
      (finally (au/destroy-stream! s)))))

(defn- wav-bytes [freq ^shorts samples]
  (io/with-io [s (io/dynamic)]
    (io/write-bytes! s "RIFF") (io/write-num! s :u32-le (+ 36 (* 2 (alength samples))))
    (io/write-bytes! s "WAVEfmt ") (io/write-num! s :u32-le 16)
    (io/write-num! s :u16-le 1) (io/write-num! s :u16-le 1)
    (io/write-num! s :u32-le freq) (io/write-num! s :u32-le (* 2 freq))
    (io/write-num! s :u16-le 2) (io/write-num! s :u16-le 16)
    (io/write-bytes! s "data") (io/write-num! s :u32-le (* 2 (alength samples)))
    (doseq [v samples] (io/write-num! s :s16-le v))
    (io/contents s)))

(deftest wav-and-convert
  (let [{:keys [spec data]} (au/load-wav-io (io/from-bytes (wav-bytes 22050 (short-array [100 -100]))) true)]
    (is (= {:format :s16 :channels 1 :freq 22050} spec))
    (is (= [100 0 -100 -1] (vec data)))
    (is (= [100 0 100 0 -100 -1 -100 -1] (vec (au/convert spec data (assoc spec :channels 2)))))))

(deftest mixing
  (is (= [15 0 25 0] (vec (au/mix (byte-array [10 0 20 0]) (byte-array [5 0 5 0]) :s16 1.0)))))

(deftest device-stream-callback
  (let [calls (atom 0)
        s (au/open-device-stream :playback {:format :f32 :channels 1 :freq 48000}
                                 (fn [stream additional _]
                                   (swap! calls inc)
                                   (au/put! stream (float-array (quot additional 4)))))]
    (try
      (is (au/stream-device-paused? s) "a device stream starts paused")
      (au/resume-stream-device! s)
      (timer/delay! 250)
      (is (pos? @calls) "the dummy device pulled from the callback")
      (finally (au/destroy-stream! s)))))
