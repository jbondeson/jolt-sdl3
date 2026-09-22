(ns sdl3.timer
  "Time and timers: SDL_timer.h."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.raw.timer :as timer]))

(defsdl ticks timer/get-ticks
  :doc "Milliseconds since SDL initialized.")
(defsdl ticks-ns timer/get-ticks-ns
  :doc "Nanoseconds since SDL initialized.")
(defsdl performance-counter timer/get-performance-counter
  :doc "A high-resolution counter; divide differences by performance-frequency for seconds.")
(defsdl performance-frequency timer/get-performance-frequency)

(defn seconds
  "The performance counter as seconds (a double), for frame timing."
  []
  (/ (double (timer/get-performance-counter)) (double (timer/get-performance-frequency))))

(defn delay! "SDL_Delay: sleep `ms` milliseconds (the collector may run meanwhile)." [ms] (timer/delay (int ms)) nil)
(defn delay-ns! "SDL_DelayNS." [ns] (timer/delay-ns (long ns)) nil)
(defn delay-precise! "SDL_DelayPrecise: SDL_DelayNS with a busy-wait at the end, for frame pacing." [ns] (timer/delay-precise (long ns)) nil)

;; ---------------------------------------------------------------------------
;; timers
;; ---------------------------------------------------------------------------

;; id -> the arena owning the callback. An automatic arena is released once the
;; collector reclaims it, i.e. some time after remove-timer! drops it from here —
;; the arena kind jolt.ffi documents for a callback C calls from its own thread.
(def ^:private timers (atom {}))

(defn add-timer!
  "SDL_AddTimer: after `ms` milliseconds call (f interval) on SDL's timer thread,
  then again every interval it answers (0 stops it). Answers the timer id.

  The callback runs on a thread jolt did not start: keep it short, and hand work
  to the main loop with sdl3.events/push-event! rather than touching a window or
  renderer from it. Release it with remove-timer!, also once it has answered 0."
  [ms f]
  (let [arena (ffi/auto-arena)
        cb (ffi/callback arena
                         (fn [_userdata _id interval] (long (or (f interval) 0)))
                         [:pointer :uint32 :uint32] :uint32 :collect-safe)
        id (timer/add-timer (int ms) cb ffi/null)]
    (when (zero? id) (throw (core/sdl-error "SDL_AddTimer")))
    (swap! timers assoc id arena)
    id))

(defn add-timer-ns!
  "SDL_AddTimerNS: add-timer! with nanoseconds."
  [ns f]
  (let [arena (ffi/auto-arena)
        cb (ffi/callback arena
                         (fn [_userdata _id interval] (long (or (f interval) 0)))
                         [:pointer :uint32 :uint64] :uint64 :collect-safe)
        id (timer/add-timer-ns (long ns) cb ffi/null)]
    (when (zero? id) (throw (core/sdl-error "SDL_AddTimerNS")))
    (swap! timers assoc id arena)
    id))

(defn remove-timer!
  "SDL_RemoveTimer; answers whether the timer was still registered."
  [id]
  (let [ok (timer/remove-timer id)]
    (swap! timers dissoc id)
    ok))
