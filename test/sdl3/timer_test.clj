(ns sdl3.timer-test
  (:require [clojure.test :refer [deftest is]]
            [sdl3.core :as sdl]
            [sdl3.timer :as timer]))

(deftest clocks
  (sdl/init! :events)
  (try
    (let [t0 (timer/ticks) s0 (timer/seconds)]
      (timer/delay! 20)
      (is (>= (- (timer/ticks) t0) 15))
      (is (>= (- (timer/seconds) s0) 0.015))
      (is (pos? (timer/performance-frequency)))
      (is (pos? (timer/ticks-ns))))
    (finally (sdl/quit!))))

(deftest timers-fire-on-sdl-thread
  (sdl/init! :events)
  (try
    (let [hits (atom 0)
          id (timer/add-timer! 10 (fn [interval] (swap! hits inc) (if (< @hits 3) interval 0)))]
      (is (pos? id))
      ;; poll rather than sleep a fixed time: a busy CI machine can be slow to fire
      (loop [waited 0] (when (and (< @hits 3) (< waited 3000)) (timer/delay! 10) (recur (+ waited 10))))
      (timer/delay! 50)
      (is (= 3 @hits) "ran three times then answered 0 to stop")
      (is (false? (timer/remove-timer! id)) "already stopped itself"))
    (let [id (timer/add-timer! 1000 (fn [_] 0))]
      (is (true? (timer/remove-timer! id)) "removed while pending"))
    (finally (sdl/quit!))))
