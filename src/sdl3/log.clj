(ns sdl3.log
  "SDL's logging: SDL_log.h. Categories are keywords from sdl3.consts/log-category
  (:application :error :assert :system :audio :video :render :input :test :gpu
  :custom), priorities from sdl3.consts/log-priority (:trace :verbose :debug
  :info :warn :error :critical). Messages are built with str from the parts,
  never interpreted as printf formats."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core]
            [sdl3.consts :as c]
            [sdl3.raw.log :as log]))

(defn- cat [k] (core/enum c/log-category k))
(defn- pri [k] (core/enum c/log-priority k))

(defn log!
  "SDL_Log: an :info message in the :application category."
  [& parts]
  (log/log "%s" (apply str parts))
  nil)

(defn message!
  "SDL_LogMessage with a category and priority."
  [category priority & parts]
  (log/log-message (cat category) (pri priority) "%s" (apply str parts))
  nil)

(defn trace! [category & parts] (apply message! category :trace parts))
(defn verbose! [category & parts] (apply message! category :verbose parts))
(defn debug! [category & parts] (apply message! category :debug parts))
(defn info! [category & parts] (apply message! category :info parts))
(defn warn! [category & parts] (apply message! category :warn parts))
(defn error! [category & parts] (apply message! category :error parts))
(defn critical! [category & parts] (apply message! category :critical parts))

(defn set-priorities!
  "SDL_SetLogPriorities: every category to `priority`."
  [priority]
  (log/set-log-priorities (pri priority))
  nil)

(defn set-priority! [category priority]
  (log/set-log-priority (cat category) (pri priority))
  nil)

(defn priority [category]
  (core/unenum c/log-priority-names (log/get-log-priority (cat category))))

(defn reset-priorities! []
  (log/reset-log-priorities)
  nil)

(defn set-priority-prefix!
  "SDL_SetLogPriorityPrefix: the text put before messages of `priority` (nil: none)."
  [priority prefix]
  (core/check-bool "SDL_SetLogPriorityPrefix" (log/set-log-priority-prefix (pri priority) prefix))
  nil)

;; the callback lives for the process: SDL may call it from any thread, at any time
(def ^:private output-arena (ffi/global-arena))

(defn set-output-function!
  "SDL_SetLogOutputFunction: route every message to (f category priority message)
  instead of SDL's default (stderr). Called from whichever thread logs."
  [f]
  (let [cb (ffi/callback output-arena
                         (fn [_userdata category priority message]
                           (f (core/unenum c/log-category-names category)
                              (core/unenum c/log-priority-names priority)
                              (when-not (ffi/null? message) (ffi/ptr->string message)))
                           nil)
                         [:pointer :int :int :pointer] :void :collect-safe)]
    (log/set-log-output-function cb ffi/null)
    nil))

(defn reset-output-function!
  "Back to SDL's default output."
  []
  (log/set-log-output-function (log/get-default-log-output-function) ffi/null)
  nil)
