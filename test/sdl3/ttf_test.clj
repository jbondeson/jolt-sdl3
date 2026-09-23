(ns sdl3.ttf-test
  "SDL_ttf, against a font that ships with the OS. Skipped, with a note, when
  libSDL3_ttf is not installed or no system font is found."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.surface :as s]
            [sdl3.test-util :as tu]
            [sdl3.ttf :as ttf]))

(def ^:dynamic *font* nil)

(use-fixtures :once
  (fn [f]
    (let [path (tu/system-font)]
      (cond
        (not (ttf/available?)) (tu/skip-or-fail "sdl3.ttf-test" "libSDL3_ttf not installed")
        (not path) (tu/skip-or-fail "sdl3.ttf-test" "no system font found")
        :else (ttf/with-ttf
                (ttf/with-font [font path 24]
                  (binding [*font* font] (f))))))))

(deftest library
  (is (= 3 (:major (ttf/version))))
  (is (ttf/initialized?))
  (is (pos? (:major (ttf/freetype-version))) "FreeType reports its version once initialized"))

(deftest font-queries
  (is (string? (ttf/family-name *font*)))
  (is (== 24.0 (ttf/size *font*)))
  (is (pos? (ttf/height *font*)))
  (is (pos? (ttf/ascent *font*)))
  (is (neg? (ttf/descent *font*)))
  (is (= 400 (ttf/weight *font*)))
  (is (ttf/scalable? *font*))
  (testing "styles round-trip as sets"
    (ttf/set-style! *font* [:bold :underline])
    (is (= #{:bold :underline} (ttf/style *font*)))
    (ttf/set-style! *font* nil)
    (is (= #{} (ttf/style *font*))))
  (testing "hinting and alignment round-trip as keywords"
    (ttf/set-hinting! *font* :light)
    (is (= :light (ttf/hinting *font*)))
    (ttf/set-hinting! *font* :normal)
    (ttf/set-wrap-alignment! *font* :center)
    (is (= :center (ttf/wrap-alignment *font*)))
    (ttf/set-wrap-alignment! *font* :left)))

(deftest glyphs-and-measuring
  (is (ttf/has-glyph? *font* \A))
  (is (pos? (:advance (ttf/glyph-metrics *font* \A))))
  (is (= "Latn" (ttf/glyph-script \A)))
  (is (= "Arab" (ttf/glyph-script 0x0627)))
  (let [[w h] (ttf/string-size *font* "Hello")]
    (is (pos? w))
    (is (pos? h))
    (let [[ww wh] (ttf/string-size *font* "Hello there world" (quot w 1))]
      (is (<= ww w))
      (is (> wh h) "wrapping onto more lines makes it taller")))
  (let [{:keys [width text]} (ttf/measure-string *font* "Hello there" 40)]
    (is (<= width 40))
    (is (< 0 (count text) (count "Hello there")))
    (is (.startsWith "Hello there" text))))

(deftest rendering-to-surfaces
  (doseq [mode [:blended :solid :shaded :lcd]]
    (testing (name mode)
      (let [surf (ttf/render-text *font* "Hi!" {:mode mode :color [255 0 0]})]
        (is (pos? (s/width surf)))
        (s/destroy-surface! surf))))
  (let [one (ttf/render-text *font* "wrap me please" {})
        wrapped (ttf/render-text *font* "wrap me please" {:wrap 40})]
    (is (> (s/height wrapped) (s/height one)))
    (s/destroy-surface! one)
    (s/destroy-surface! wrapped))
  (let [g (ttf/render-glyph *font* \g)]
    (is (pos? (s/width g)))
    (s/destroy-surface! g)))

(deftest text-objects
  (let [eng (ttf/create-surface-text-engine)
        t (ttf/create-text eng *font* "héllo")
        dst (s/create-surface 200 50 :rgba8888)]
    (try
      (ttf/append-text! t " world")
      (is (= "héllo world" (ttf/text t)))
      (is (= 1 (ttf/num-lines t)))
      (ttf/set-text-color! t [0 255 0])
      (is (= {:r 0 :g 255 :b 0 :a 255} (ttf/text-color t)))
      (ttf/draw-surface-text! t 0 0 dst)
      (testing "substrings are byte-addressed clusters"
        (let [first-sub (ttf/substring t 0)
              e-acute (ttf/next-substring t first-sub)]
          (is (= {:offset 0 :length 1} (select-keys first-sub [:offset :length])))
          (is (contains? (:flags first-sub) :text-start))
          (is (= {:offset 1 :length 2} (select-keys e-acute [:offset :length])) "é is two UTF-8 bytes")
          (is (= 0 (:offset (ttf/previous-substring t e-acute))))))
      (is (= 12 (:length (ttf/substring-for-line t 0))))
      (ttf/set-wrap-width! t 50)
      (is (> (ttf/num-lines t) 1))
      (ttf/delete-text! t 0 7)                    ; 7 UTF-8 bytes: h, é (2), l, l, o, space
      (is (= "world" (ttf/text t)))
      (finally
        (ttf/destroy-text! t)
        (ttf/destroy-text-engine! eng)
        (s/destroy-surface! dst)))))

(deftest errors
  (is (thrown-with-msg? clojure.lang.ExceptionInfo #"TTF_OpenFont failed" (ttf/open-font "/nonexistent/jolt-sdl3.ttf" 12)))
  (is (thrown? clojure.lang.ExceptionInfo (ttf/render-text *font* "x" {:mode :sparkly}))))
