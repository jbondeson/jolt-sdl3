(ns sdl3.image-test
  "SDL_image. Skipped, with a note, when libSDL3_image is not installed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.image :as img]
            [sdl3.io :as io]
            [sdl3.surface :as s]
            [sdl3.test-util :as tu]))

(use-fixtures :once
  (fn [f]
    (if (img/available?)
      (f)
      (tu/skip-or-fail "sdl3.image-test" "libSDL3_image not installed"))))

(deftest version
  (is (= 3 (:major (img/version)))))

(defn- red-surface []
  (let [src (s/create-surface 32 16 :rgba8888)]
    (s/fill-rect! src nil (s/map-rgba src 200 50 25 255))
    src))

(deftest formats-round-trip
  (let [dir (tu/temp-dir "jolt-sdl3-image")
        src (red-surface)]
    (try
      (doseq [fmt [:png :jpg :webp :gif :bmp :tga :ico]]
        (testing (name fmt)
          (let [path (str dir "/t." (name fmt))]
            (img/save-as! src path fmt)
            (let [back (img/load path)]
              (try
                (is (= [32 16] [(s/width back) (s/height back)]))
                (let [{:keys [r g b]} (s/read-pixel back 3 3)]
                  (is (< (Math/abs (- r 200)) 4) "red survives, allowing for lossy formats")
                  (is (< (Math/abs (- g 50)) 4))
                  (is (< (Math/abs (- b 25)) 4)))
                (finally (s/destroy-surface! back))))
            (when-not (= fmt :tga)                       ; TGA has no signature to detect
              (is (= fmt (io/with-io [st (io/from-file path "rb")] (img/format-of st))))))))
      (testing "save! picks the format from the extension"
        (let [path (str dir "/by-extension.png")]
          (img/save! src path)
          (is (true? (io/with-io [st (io/from-file path "rb")] (img/format? st :png))))))
      (finally (s/destroy-surface! src)))))

(deftest streams
  (let [src (red-surface)
        buf (io/dynamic)]
    (try
      (img/save-io! src buf false :png)
      (io/seek! buf 0)
      (let [back (img/load-io buf false)]
        (is (= 32 (s/width back)))
        (s/destroy-surface! back))
      (finally (s/destroy-surface! src) (io/close! buf)))))

(deftest animations
  (let [path (str (tu/temp-dir "jolt-sdl3-anim") "/a.gif")
        a (red-surface)
        b (s/create-surface 32 16 :rgba8888)]
    (try
      (s/fill-rect! b nil (s/map-rgba b 0 0 255 255))
      (let [enc (img/create-encoder path)]
        (img/add-frame! enc a 100)
        (img/add-frame! enc b 200)
        (img/close-encoder! enc))
      (let [anim (img/load-animation path)
            {:keys [w h count frames delays]} (img/animation-info anim)]
        (is (= [32 16 2] [w h count]))
        (is (= [100 200] delays))
        (is (= 2 (clojure.core/count frames)))
        (img/free-animation! anim))
      (let [dec (img/create-decoder path)
            durations (loop [acc []]
                        (if-let [{:keys [surface duration-ms]} (img/next-frame dec)]
                          (do (s/destroy-surface! surface) (recur (conj acc duration-ms)))
                          acc))]
        (is (= [100 200] durations))
        (is (= :complete (img/decoder-status dec)))
        (img/close-decoder! dec))
      (finally (s/destroy-surface! a) (s/destroy-surface! b)))))

(deftest xpm-and-errors
  (let [x (img/read-xpm ["2 2 2 1" "a c #FF0000" "b c #0000FF" "ab" "ba"])]
    (is (= [2 2] [(s/width x) (s/height x)]))
    (is (= {:r 255 :g 0 :b 0 :a 255} (s/read-pixel x 0 0)))
    (s/destroy-surface! x))
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"IMG_Load failed" (img/load "/nonexistent/jolt-sdl3.png")))
  (is (thrown? clojure.lang.ExceptionInfo (img/save-as! nil "x.qoi" :qoi)) "QOI loads but does not save"))
