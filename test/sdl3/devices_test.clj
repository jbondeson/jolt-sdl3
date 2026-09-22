(ns sdl3.devices-test
  "Sensors, haptics and cameras. These depend on hardware, so the tests assert
  the shape of what SDL reports and never open a device (opening a camera asks
  the user for permission)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.camera :as cam]
            [sdl3.haptic :as hp]
            [sdl3.sensor :as sn]
            [sdl3.raw.haptic :as raw-haptic]))

(use-fixtures :each (fn [f] (sdl/init! :sensor :haptic :camera) (try (f) (finally (sdl/quit!)))))

(deftest sensors
  (is (vector? (sn/sensors)))
  (doseq [id (sn/sensors)]
    (is (keyword? (:type (sn/info-for-id id)))))
  (is (thrown? clojure.lang.ExceptionInfo (sn/open 999999))))

(deftest cameras
  (is (seq (cam/drivers)))
  (is (vector? (cam/cameras)))
  (doseq [id (cam/cameras)]
    (is (string? (cam/camera-name id)))
    (is (#{:front-facing :back-facing :unknown} (cam/position id)))
    (doseq [spec (cam/supported-formats id)]
      (is (keyword? (:format spec)))
      (is (pos? (:width spec))))))

(defn- encode [effect layout]
  (with-open [a (ffi/confined-arena)]
    (ffi/read (#'sdl3.haptic/write-effect! a effect) layout)))

(deftest haptic-effect-encoding
  (is (vector? (hp/haptics)))
  (testing "periodic"
    (let [e (encode {:type :sine :direction {:type :cartesian :dir [1 0]} :length :infinity :period 100 :magnitude 20000}
                    raw-haptic/haptic-periodic)]
      (is (= 2 (:type e)))
      (is (= {:type 1 :dir [1 0 0]} (:direction e)))
      (is (= 4294967295 (:length e)))
      (is (= 20000 (:magnitude e)))))
  (testing "condition, with per-axis arrays"
    (let [e (encode {:type :spring :length 500 :right-sat [1 2 3] :left-coeff [-4 5 6]} raw-haptic/haptic-condition)]
      (is (= 128 (:type e)))
      (is (= [1 2 3] (:right-sat e)))
      (is (= [-4 5 6] (:left-coeff e)))))
  (testing "left-right and custom"
    (is (= 2048 (:type (encode {:type :leftright :length 100 :large-magnitude 1000} raw-haptic/haptic-left-right))))
    (with-open [a (ffi/confined-arena)]
      ;; the sample data lives in the arena, so read it before the arena closes
      (let [e (ffi/read (#'sdl3.haptic/write-effect! a {:type :custom :channels 1 :period 10 :samples 2 :data [100 65535]})
                        raw-haptic/haptic-custom)]
        (is (= [100 65535] (vec (map #(bit-and % 0xffff) (ffi/read-array (:data e) :uint16 2))))))))
  (is (thrown? clojure.lang.ExceptionInfo (encode {:type :wobble} raw-haptic/haptic-constant))))
