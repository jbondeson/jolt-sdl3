(ns sdl3.sensor
  "Device sensors — accelerometers and gyroscopes on phones, tablets and some
  laptops: SDL_sensor.h. (Controller sensors are in sdl3.gamepad.) Needs the
  :sensor subsystem. A sensor is an instance id before open and a pointer after;
  its readings also arrive as :sensor-update events.

  :accel data is [x y z] in m/s² including gravity (sdl3.consts/misc
  :standard-gravity); :gyro is [x y z] in radians per second."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.sensor :as sensor]))

(defn- sensor-type [v] (core/unenum c/sensor-type-names v))

(defn sensors "SDL_GetSensors: the connected sensors' instance ids." [] (core/with-count sensor/get-sensors))

(defn info-for-id
  "{:id :name :type :non-portable-type} for sensor `id` before it is opened."
  [id]
  {:id id
   :name (sensor/get-sensor-name-for-id id)
   :type (sensor-type (sensor/get-sensor-type-for-id id))
   :non-portable-type (sensor/get-sensor-non-portable-type-for-id id)})

(defsdl name-for-id sensor/get-sensor-name-for-id :nullable true)
(defn type-for-id [id] (sensor-type (sensor/get-sensor-type-for-id id)))

(defsdl open sensor/open-sensor)
(defsdl close! sensor/close-sensor)
(defsdl from-id sensor/get-sensor-from-id :nullable true)
(defsdl properties sensor/get-sensor-properties)
(defsdl sensor-name sensor/get-sensor-name :nullable true)
(defsdl id sensor/get-sensor-id)
(defsdl non-portable-type sensor/get-sensor-non-portable-type)
(defn sensor-type-of "The opened sensor's type: :accel :gyro :accel-l :gyro-l :accel-r :gyro-r :unknown." [s] (sensor-type (sensor/get-sensor-type s)))
(defsdl update! sensor/update-sensors
  :doc "Poll sensor state; only needed when sensor events are disabled.")

(defn data
  "SDL_GetSensorData: the latest `n` values (default 3) as a vector of floats."
  ([s] (data s 3))
  ([s n]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (* 4 n))]
       (core/check-bool "SDL_GetSensorData" (sensor/get-sensor-data s p (int n)))
       (vec (ffi/read-array p :float n))))))
