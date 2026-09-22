(ns sdl3.events-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.consts :as c]
            [sdl3.events :as ev]
            [sdl3.keyboard :as kb]
            [sdl3.raw.events :as raw]))

(use-fixtures :each (fn [f] (sdl/init! :events) (try (f) (finally (sdl/quit!)))))

(deftest user-events-round-trip
  (ev/flush-events!)
  (is (nil? (ev/poll!)))
  (is (true? (ev/push-event! {:type :user :code 42 :data1 7})))
  (let [e (ev/poll!)]
    (is (= :user (:type e)))
    (is (= 42 (:code e)))
    (is (= 7 (:data1 e)))
    (is (= 0 (:data2 e)))
    (is (integer? (:timestamp e))))
  (is (nil? (ev/poll!))))

(deftest registered-types-and-drain
  (let [t (ev/register-events! 2)]
    (is (>= t (c/event-type :user)))
    (ev/push-event! {:type t :code 1})
    (ev/push-event! {:type (inc t) :code 2})
    (ev/push-event! {:type :user :code 3})
    (is (true? (ev/has-events?)))
    ;; the first registered type is SDL_EVENT_USER itself, which decodes by name
    (is (= [[(sdl/unenum c/event-type-names t) 1] [(inc t) 2] [:user 3]]
           (mapv (juxt :type :code) (ev/poll-all!))))
    (is (false? (ev/has-events?)))))

(deftest peep-and-wait-timeout
  (ev/push-event! {:type :user :code 9})
  (is (= [9] (mapv :code (ev/peep-events :peek 4))) "peek leaves it")
  (is (= 9 (:code (ev/wait-timeout! 100))))
  (is (nil? (ev/wait-timeout! 10))))

(deftest keyboard-event-decoding
  (let [p (ffi/alloc ev/event-size)]
    (try
      (ffi/write p raw/keyboard-event
                 {:type (c/event-type :key-down) :reserved 0 :timestamp 5 :window-id 1 :which 0
                  :scancode (kb/scancode :a) :key (kb/keycode :a)
                  :mod (sdl/flags c/keymod [:lshift :num]) :raw 0 :down true :repeat false})
      (let [e (ev/decode p)]
        (is (= :key-down (:type e)))
        (is (= :a (:scancode e)))
        (is (= :a (:key e)))
        (is (= #{:lshift :num} (:mod e)))
        (is (kb/mod? (:mod e) :shift))
        (is (not (kb/mod? (:mod e) :ctrl)))
        (is (true? (:down e)))
        (is (false? (:repeat e)))
        (is (not (contains? e :reserved))))
      (finally (ffi/free p)))))

(deftest mouse-and-window-event-decoding
  (let [p (ffi/alloc ev/event-size)]
    (try
      (ffi/write p raw/mouse-button-event
                 {:type (c/event-type :mouse-button-down) :reserved 0 :timestamp 1 :window-id 1 :which 0
                  :button 3 :down true :clicks 2 :padding 0 :x 10.5 :y 20.25})
      (is (= {:type :mouse-button-down :timestamp 1 :window-id 1 :which 0 :button :right :down true :clicks 2 :padding 0 :x 10.5 :y 20.25}
             (ev/decode p)))
      (ffi/write p raw/mouse-motion-event
                 {:type (c/event-type :mouse-motion) :reserved 0 :timestamp 1 :window-id 1 :which 0
                  :state (bit-or (c/mouse-button :lmask) (c/mouse-button :x2mask)) :x 1.0 :y 2.0 :xrel 0.5 :yrel -0.5})
      (is (= #{:left :x2} (:state (ev/decode p))))
      (ffi/write p raw/window-event
                 {:type (c/event-type :window-resized) :reserved 0 :timestamp 1 :window-id 1 :data1 800 :data2 600})
      (is (= {:type :window-resized :timestamp 1 :window-id 1 :data1 800 :data2 600} (ev/decode p)))
      (testing "an unknown type decodes as common with its integer type"
        (ffi/write p :uint32 0x7777)
        (is (= 0x7777 (:type (ev/decode p)))))
      (finally (ffi/free p)))))
