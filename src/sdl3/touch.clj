(ns sdl3.touch
  "Touch surfaces and pens: SDL_touch.h, SDL_pen.h. Touches and pen strokes
  arrive as events (:finger-down, :finger-motion, :pen-down, :pen-axis ...);
  this is the state and device side. Finger coordinates are 0.0-1.0 across the
  device."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.touch :as touch]
            [sdl3.raw.pen :as pen]))

(defn devices
  "SDL_GetTouchDevices: the touch devices' ids (64-bit)."
  []
  (with-outs [n :int]
    (let [p (touch/get-touch-devices n)]
      (if (ffi/null? p)
        []
        (let [ids (mapv (fn [i] (ffi/read p :uint64 (* 8 i))) (range (ffi/read n :int)))]
          (core/free! p)
          ids)))))

(defsdl device-name touch/get-touch-device-name)

(defn device-type
  "SDL_GetTouchDeviceType: :direct (a touchscreen), :indirect-absolute (a
  trackpad mapped to the window) or :indirect-relative (a trackpad moving a cursor)."
  [id]
  (core/unenum c/touch-device-type-names (touch/get-touch-device-type id)))

(defn fingers
  "SDL_GetTouchFingers: the fingers down on device `id` now, as
  [{:id :x :y :pressure} ...]."
  [id]
  (with-outs [n :int]
    (let [p (touch/get-touch-fingers id n)]
      (if (ffi/null? p)
        []
        (let [fs (mapv (fn [i] (ffi/read (ffi/read p :pointer (* i (ffi/sizeof :pointer))) touch/finger))
                       (range (ffi/read n :int)))]
          (core/free! p)
          fs)))))

(defn pen-device-type
  "SDL_GetPenDeviceType of the pen in an event's :which: :direct (drawing on the
  screen), :indirect (a tablet) or :unknown."
  [pen-id]
  (core/unenum c/pen-device-type-names (pen/get-pen-device-type pen-id)))
