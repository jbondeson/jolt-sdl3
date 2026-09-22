(ns sdl3.system-test
  "System queries, time, touch, pixels, blend modes and HID. Hardware-dependent
  answers are checked for shape only."
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.core :as sdl]
            [sdl3.hid :as hid]
            [sdl3.pixels :as px]
            [sdl3.render :as r]
            [sdl3.system :as sys]
            [sdl3.time :as t]
            [sdl3.touch :as touch]))

(deftest cpu-locale-power
  (let [{:keys [logical-cores ram-mib features]} (sys/cpu-info)]
    (is (pos? logical-cores))
    (is (pos? ram-mib))
    (is (set? features)))
  (is (every? #(string? (:language %)) (sys/preferred-locales)))
  (is (keyword? (:state (sys/power-info))))
  (is (keyword? (sys/sandbox)))
  (is (boolean? (sys/tablet?))))

(deftest shared-objects
  (let [lib (if (re-find #"(?i)mac" (System/getProperty "os.name")) "/usr/lib/libSystem.B.dylib" "libc.so.6")
        so (sys/load-object lib)]
    (try
      (is (pos? (sys/load-function so "strlen")))
      (is (thrown? clojure.lang.ExceptionInfo (sys/load-function so "no_such_symbol_jolt")))
      (finally (sys/unload-object! so)))))

(deftest calendar
  (let [now (t/now)
        local (t/->date-time now)
        utc (t/->date-time now false)]
    (is (= now (t/->time local)) "local date-time round-trips")
    (is (= now (t/->time utc)) "UTC date-time round-trips")
    (is (<= 1 (:month local) 12)))
  (is (= 29 (t/days-in-month 2024 2)))
  (is (= 28 (t/days-in-month 2026 2)))
  (is (= 2 (t/day-of-week 2026 9 22)) "a Tuesday")
  (is (= 364 (t/day-of-year 2026 12 31)))
  (is (= 0 (t/->time {:year 1970 :month 1 :day 1 :hour 0 :minute 0 :second 0 :nanosecond 0 :utc-offset 0})))
  (is (#{:yyyymmdd :ddmmyyyy :mmddyyyy} (:date-format (t/locale-preferences))))
  (is (thrown? clojure.lang.ExceptionInfo (t/days-in-month 2026 13))))

(deftest touch-devices
  (sdl/init! :video)
  (try
    (doseq [id (touch/devices)]
      (is (keyword? (touch/device-type id)))
      (is (vector? (touch/fingers id))))
    (finally (sdl/quit!))))

(deftest pixel-formats-and-palettes
  (is (= "SDL_PIXELFORMAT_RGBA8888" (px/format-name :rgba8888)))
  (is (= {:bpp 32 :rmask 0xff000000 :gmask 0xff0000 :bmask 0xff00 :amask 0xff} (px/masks :rgba8888)))
  (is (= :abgr8888 (px/format-for-masks (px/masks :abgr8888))))
  (is (= 4 (:bytes-per-pixel (px/details :rgba8888))))
  (let [v (px/map-rgba :rgba8888 1 2 3 4)]
    (is (= 0x01020304 v))
    (is (= {:r 1 :g 2 :b 3 :a 4} (px/rgba v :rgba8888))))
  (let [pal (px/create-palette 3 [[255 0 0] {:r 0 :g 255 :b 0 :a 128}])]
    (try
      (is (= [{:r 255 :g 0 :b 0 :a 255} {:r 0 :g 255 :b 0 :a 128} {:r 255 :g 255 :b 255 :a 255}] (px/colors pal)))
      (is (= {:r 0 :g 255 :b 0 :a 128} (px/rgba 1 :index8 pal)))
      (finally (px/destroy-palette! pal)))))

(deftest custom-blend-modes
  (is (integer? (r/compose-blend-mode {:src-color-factor :src-alpha :dst-color-factor :one-minus-src-alpha :color-operation :add
                                       :src-alpha-factor :one :dst-alpha-factor :one-minus-src-alpha :alpha-operation :add}))))

(deftest hid-enumeration
  (hid/with-hid
    (let [devs (hid/enumerate)]
      (is (vector? devs))
      (doseq [d devs]
        (is (integer? (:vendor-id d)))
        (is (or (nil? (:product-string d)) (string? (:product-string d))))))
    (is (integer? (hid/device-change-count)))))

(deftest uint32-values-above-int-range
  (testing "a Uint32 pixel value with the top bit set is not cast through a signed int"
    (let [s ((requiring-resolve 'sdl3.surface/create-surface) 2 2 :rgba8888)
          fill! (requiring-resolve 'sdl3.surface/fill-rect!)
          read-pixel (requiring-resolve 'sdl3.surface/read-pixel)]
      (try
        (fill! s nil (px/map-rgba :rgba8888 200 10 20 255))
        (is (= {:r 200 :g 10 :b 20 :a 255} (read-pixel s 0 0)))
        (finally ((requiring-resolve 'sdl3.surface/destroy-surface!) s)))))
  (let [a ((requiring-resolve 'sdl3.thread/atomic-u32) 0xFFFFFFF0)]
    (is (= 0xFFFFFFF0 ((requiring-resolve 'sdl3.thread/get-u32) a)))
    ((requiring-resolve 'sdl3.thread/free!) a)))
