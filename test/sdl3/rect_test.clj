(ns sdl3.rect-test
  (:require [clojure.test :refer [deftest is]]
            [jolt.ffi :as ffi]
            [sdl3.rect :as rect]))

(deftest pure-helpers
  (is (rect/rect-empty? nil))
  (is (rect/rect-empty? {:x 0 :y 0 :w 0 :h 5}))
  (is (not (rect/rect-empty? [0 0 1 1])))
  (is (rect/rects-equal? {:x 1 :y 2 :w 3 :h 4 :extra true} [1 2 3 4]))
  (is (rect/point-in-rect? [3 3] [0 0 10 10]))
  (is (not (rect/point-in-rect? {:x 10 :y 3} [0 0 10 10])))
  (is (= {:x 1.0 :y 2.0 :w 3.0 :h 4.0} (rect/rect->frect [1 2 3 4]))))

(deftest c-backed-geometry
  (is (rect/has-intersection? [0 0 10 10] [5 5 10 10]))
  (is (not (rect/has-intersection? [0 0 10 10] [20 20 10 10])))
  (is (= {:x 5 :y 5 :w 5 :h 5} (rect/intersection [0 0 10 10] [5 5 10 10])))
  (is (nil? (rect/intersection [0 0 10 10] [20 20 10 10])))
  (is (= {:x 0 :y 0 :w 25 :h 25} (rect/union [0 0 10 10] [20 20 5 5])))
  (is (= {:x 5.0 :y 5.0 :w 5.0 :h 5.0} (rect/intersection-float [0 0 10 10] [5 5 10 10])))
  (is (= {:x 1 :y 2 :w 5 :h 8} (rect/enclosing-points [[1 2] [5 9] [3 3]])))
  (is (nil? (rect/enclosing-points [[50 50]] [0 0 10 10]))))

(deftest memory-round-trips
  (with-open [a (ffi/confined-arena)]
    (let [p (rect/alloc-frect a {:x 1 :y 2 :w 3 :h 4})]
      (is (= {:x 1.0 :y 2.0 :w 3.0 :h 4.0} (rect/read-frect p))))
    (let [[p n] (rect/frects a [[0 0 1 1] {:x 2 :y 2 :w 2 :h 2}])]
      (is (= 2 n))
      (is (= {:x 2.0 :y 2.0 :w 2.0 :h 2.0} (rect/read-frect (ffi/slice p (ffi/layout-size rect/frect))))))
    (let [[p n] (rect/points a [[1 2] [3 4]])]
      (is (= 2 n))
      (is (= {:x 3 :y 4} (rect/read-point (ffi/slice p (ffi/layout-size rect/point))))))))
