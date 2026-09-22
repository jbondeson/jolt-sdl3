(ns sdl3.time
  "Wall-clock time and calendar dates: SDL_time.h. (Frame timing is in
  sdl3.timer.) An SDL_Time is nanoseconds since the Unix epoch, as an integer;
  a date-time is a map

      {:year 2026 :month 9 :day 22 :hour 16 :minute 5 :second 30
       :nanosecond 0 :day-of-week 2 :utc-offset -18000}

  with :month 1-12, :day-of-week 0 (Sunday) to 6, and :utc-offset in seconds."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.time :as time]))

(defn now
  "SDL_GetCurrentTime: nanoseconds since the Unix epoch."
  []
  (with-outs [t :int64]
    (core/check-bool "SDL_GetCurrentTime" (time/get-current-time t))
    (ffi/read t :int64)))

(defn ->date-time
  "SDL_TimeToDateTime: an SDL_Time as a date-time map, in local time (default) or UTC."
  ([t] (->date-time t true))
  ([t local?]
   (with-open [a (ffi/confined-arena)]
     (let [dt (ffi/alloc a time/date-time)]
       (core/check-bool "SDL_TimeToDateTime" (time/time-to-date-time (long t) dt (boolean local?)))
       (ffi/read dt time/date-time)))))

(defn ->time
  "SDL_DateTimeToTime: a date-time map (:utc-offset honored, :day-of-week
  ignored) as an SDL_Time."
  [dt]
  (with-open [a (ffi/confined-arena)]
    (with-outs [t :int64]
      (core/check-bool "SDL_DateTimeToTime"
                       (time/date-time-to-time (core/alloc-fields a time/date-time (dissoc dt :day-of-week)) t))
      (ffi/read t :int64))))

(defn local-now "The current local date-time." [] (->date-time (now) true))
(defn utc-now "The current UTC date-time." [] (->date-time (now) false))

(defn days-in-month [year month]
  (let [n (time/get-days-in-month (int year) (int month))]
    (when (neg? n) (throw (core/sdl-error "SDL_GetDaysInMonth")))
    n))

(defn day-of-year "0-365." [year month day]
  (let [n (time/get-day-of-year (int year) (int month) (int day))]
    (when (neg? n) (throw (core/sdl-error "SDL_GetDayOfYear")))
    n))

(defn day-of-week "0 (Sunday) to 6." [year month day]
  (let [n (time/get-day-of-week (int year) (int month) (int day))]
    (when (neg? n) (throw (core/sdl-error "SDL_GetDayOfWeek")))
    n))

(defn locale-preferences
  "SDL_GetDateTimeLocalePreferences: {:date-format :yyyymmdd|:ddmmyyyy|:mmddyyyy
  :time-format :24hr|:12hr}."
  []
  (with-outs [df :int tf :int]
    (core/check-bool "SDL_GetDateTimeLocalePreferences" (time/get-date-time-locale-preferences df tf))
    {:date-format (core/unenum c/date-format-names (ffi/read df :int))
     :time-format (case (ffi/read tf :int) 0 :24hr 1 :12hr (ffi/read tf :int))}))

(defn ->windows-filetime
  "SDL_TimeToWindows: [low high] halves of a Windows FILETIME."
  [t]
  (with-outs [lo :uint32 hi :uint32]
    (time/time-to-windows (long t) lo hi)
    [(ffi/read lo :uint32) (ffi/read hi :uint32)]))

(defn windows-filetime->time [lo hi] (time/time-from-windows (int lo) (int hi)))
