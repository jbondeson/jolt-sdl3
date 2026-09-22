(ns sdl3.joystick-test
  "Joysticks and gamepads, driven through a virtual device."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.core :as sdl]
            [sdl3.events :as ev]
            [sdl3.gamepad :as gp]
            [sdl3.joystick :as js]))

(use-fixtures :each (fn [f] (sdl/init! :gamepad) (try (f) (finally (sdl/quit!)))))

(defn- with-virtual-pad [f]
  (let [id (js/attach-virtual! {:type :gamepad :name "jolt virtual pad" :naxes 6 :nbuttons 15 :nhats 1})]
    (try (f id) (finally (js/detach-virtual! id)))))

(deftest virtual-joystick
  (with-virtual-pad
    (fn [id]
      (is (some #{id} (js/joysticks)))
      (is (js/virtual? id))
      (let [info (js/info-for-id id)]
        (is (= "jolt virtual pad" (:name info)))
        (is (= :gamepad (:type info)))
        (is (re-matches #"[0-9a-f]{32}" (:guid info))))
      (let [j (js/open id)]
        (try
          (is (= [6 15 1] [(js/num-axes j) (js/num-buttons j) (js/num-hats j)]))
          (js/set-virtual-button! j 3 true)
          (js/set-virtual-axis! j 1 -32768)
          (js/set-virtual-hat! j 0 :leftup)
          (js/update!)
          (let [{:keys [axes buttons hats]} (js/state j)]
            (is (= -32768 (nth axes 1)))
            (is (= -1.0 (js/axis-normalized j 1)))
            (is (true? (nth buttons 3)))
            (is (= [:leftup] hats)))
          (is (some #(and (= :joystick-hat-motion (:type %)) (= :leftup (:value %))) (ev/poll-all!)))
          (finally (js/close! j)))))))

(deftest virtual-gamepad
  (with-virtual-pad
    (fn [id]
      (is (gp/gamepad? id))
      (is (some #{id} (gp/gamepads)))
      (let [g (gp/open id)]
        (try
          (is (= "jolt virtual pad" (gp/gamepad-name g)))
          (is (string? (gp/mapping g)))
          (js/set-virtual-button! (gp/joystick g) 0 true)
          (js/set-virtual-axis! (gp/joystick g) 0 16384)
          (gp/update!)
          (is (gp/button? g :south))
          (is (not (gp/button? g :east)))
          (is (= 16384 (gp/axis g :leftx)))
          (is (= #{:south} (:buttons (gp/state g))))
          (is (= :a (gp/button-label g :south)))
          (let [evs (ev/poll-all!)]
            (is (some #(and (= :gamepad-button-down (:type %)) (= :south (:button %))) evs))
            (is (some #(and (= :gamepad-axis-motion (:type %)) (= :leftx (:axis %)) (= 16384 (:value %))) evs)))
          (finally (gp/close! g)))))))

(deftest name-conversions
  (is (= "a" (gp/string-for-button :south)))
  (is (= :east (gp/button-from-string "b")))
  (is (= :lefty (gp/axis-from-string "lefty")))
  (is (= "ps5" (gp/string-for-type :ps5)))
  (is (= :ps5 (gp/type-from-string "ps5")))
  (is (= :cross (gp/button-label-for-type :ps5 :south)))
  (is (some #{:south} gp/buttons))
  (is (seq (gp/mappings))))
