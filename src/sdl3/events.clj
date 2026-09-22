(ns sdl3.events
  "The event queue: SDL_events.h.

  poll!, wait! and wait-timeout! answer the next event decoded to a map, or nil:

      {:type :key-down :timestamp 123456789 :window-id 1 :which 0
       :scancode :a :key :a :mod #{:lshift} :raw 0 :down true :repeat false}

  :type is a keyword from sdl3.consts/event-type (an integer for a type SDL has
  no name for, such as one from register-events!). Every other key is the C
  field kebab-cased, with enumerations decoded to keywords, flags to sets, and
  C strings read into strings. The event structs themselves are the layouts in
  sdl3.raw.events, for code that wants to read one field of a raw event.

  The events SDL delivers are all decoded; a window event is {:type
  :window-resized :window-id 1 :data1 800 :data2 600}, a user event {:type
  :user :code 1 :data1 0 :data2 0}."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.events :as events]))

(def event-size
  "sizeof(SDL_Event)."
  (ffi/layout-size events/event))

(def types
  "Event type keyword -> integer: sdl3.consts/event-type."
  c/event-type)

;; ---------------------------------------------------------------------------
;; decoding
;; ---------------------------------------------------------------------------

(def ^:private keycode-names
  (reduce (fn [m [k v]]
            (if (or (contains? m v) (re-find #"mask$" (name k))) m (assoc m v k)))
          {} c/keycode))

(def ^:private button-names {1 :left 2 :middle 3 :right 4 :x1 5 :x2})

(def ^:private button-masks
  {:left (c/mouse-button :lmask) :middle (c/mouse-button :mmask) :right (c/mouse-button :rmask)
   :x1 (c/mouse-button :x1mask) :x2 (c/mouse-button :x2mask)})

(defn- buttons [v]
  (into #{} (for [[k m] button-masks :when (pos? (bit-and v m))] k)))

(defn- cstr [p] (when (and p (not (ffi/null? p))) (ffi/ptr->string p)))

(defn- keyboard [e]
  (-> e
      (update :scancode #(core/unenum c/scancode-names %))
      (update :key #(get keycode-names % %))
      (update :mod #(core/unflag c/keymod %))))

(def ^:private hat-names (into {} (map (fn [[k v]] [v k])) c/joystick-hat))

(def ^:private decoders
  ;; event type keyword -> [layout post-fn]
  (let [same (fn [e] e)
        strings (fn [& ks] (fn [e] (reduce (fn [e k] (update e k cstr)) e ks)))]
    (merge
     {:quit [events/quit-event same]
      :key-down [events/keyboard-event keyboard]
      :key-up [events/keyboard-event keyboard]
      :text-editing [events/text-editing-event (strings :text)]
      :text-input [events/text-input-event (strings :text)]
      :text-editing-candidates [events/text-editing-candidates-event
                                (fn [e] (assoc e :candidates (core/read-strings (:candidates e) (:num-candidates e))))]
      :keyboard-added [events/keyboard-device-event same]
      :keyboard-removed [events/keyboard-device-event same]
      :mouse-motion [events/mouse-motion-event (fn [e] (update e :state buttons))]
      :mouse-button-down [events/mouse-button-event (fn [e] (update e :button #(get button-names % %)))]
      :mouse-button-up [events/mouse-button-event (fn [e] (update e :button #(get button-names % %)))]
      :mouse-wheel [events/mouse-wheel-event (fn [e] (update e :direction #(core/unenum c/mouse-wheel-direction-names %)))]
      :mouse-added [events/mouse-device-event same]
      :mouse-removed [events/mouse-device-event same]
      :joystick-axis-motion [events/joy-axis-event same]
      :joystick-ball-motion [events/joy-ball-event same]
      :joystick-hat-motion [events/joy-hat-event (fn [e] (update e :value #(get hat-names % %)))]
      :joystick-button-down [events/joy-button-event same]
      :joystick-button-up [events/joy-button-event same]
      :joystick-added [events/joy-device-event same]
      :joystick-removed [events/joy-device-event same]
      :joystick-update-complete [events/joy-device-event same]
      :joystick-battery-updated [events/joy-battery-event (fn [e] (update e :state #(core/unenum c/power-state-names %)))]
      :gamepad-axis-motion [events/gamepad-axis-event (fn [e] (update e :axis #(core/unenum c/gamepad-axis-names %)))]
      :gamepad-button-down [events/gamepad-button-event (fn [e] (update e :button #(core/unenum c/gamepad-button-names %)))]
      :gamepad-button-up [events/gamepad-button-event (fn [e] (update e :button #(core/unenum c/gamepad-button-names %)))]
      :gamepad-added [events/gamepad-device-event same]
      :gamepad-removed [events/gamepad-device-event same]
      :gamepad-remapped [events/gamepad-device-event same]
      :gamepad-update-complete [events/gamepad-device-event same]
      :gamepad-steam-handle-updated [events/gamepad-device-event same]
      :gamepad-touchpad-down [events/gamepad-touchpad-event same]
      :gamepad-touchpad-motion [events/gamepad-touchpad-event same]
      :gamepad-touchpad-up [events/gamepad-touchpad-event same]
      :gamepad-sensor-update [events/gamepad-sensor-event (fn [e] (update e :sensor #(core/unenum c/sensor-type-names %)))]
      :finger-down [events/touch-finger-event same]
      :finger-up [events/touch-finger-event same]
      :finger-motion [events/touch-finger-event same]
      :finger-canceled [events/touch-finger-event same]
      :pinch-begin [events/pinch-finger-event same]
      :pinch-update [events/pinch-finger-event same]
      :pinch-end [events/pinch-finger-event same]
      :clipboard-update [events/clipboard-event
                         (fn [e] (assoc e :mime-types (core/read-strings (:mime-types e) (:num-mime-types e))))]
      :drop-file [events/drop-event (strings :source :data)]
      :drop-text [events/drop-event (strings :source :data)]
      :drop-begin [events/drop-event (strings :source :data)]
      :drop-complete [events/drop-event (strings :source :data)]
      :drop-position [events/drop-event (strings :source :data)]
      :audio-device-added [events/audio-device-event same]
      :audio-device-removed [events/audio-device-event same]
      :audio-device-format-changed [events/audio-device-event same]
      :sensor-update [events/sensor-event same]
      :pen-proximity-in [events/pen-proximity-event same]
      :pen-proximity-out [events/pen-proximity-event same]
      :pen-down [events/pen-touch-event same]
      :pen-up [events/pen-touch-event same]
      :pen-button-down [events/pen-button-event same]
      :pen-button-up [events/pen-button-event same]
      :pen-motion [events/pen-motion-event same]
      :pen-axis [events/pen-axis-event (fn [e] (update e :axis #(core/unenum c/pen-axis-names %)))]
      :camera-device-added [events/camera-device-event same]
      :camera-device-removed [events/camera-device-event same]
      :camera-device-approved [events/camera-device-event same]
      :camera-device-denied [events/camera-device-event same]
      :render-targets-reset [events/render-event same]
      :render-device-reset [events/render-event same]
      :render-device-lost [events/render-event same]
      :user [events/user-event same]})))

(def ^:private display-first (c/event-type :display-first))
(def ^:private display-last (c/event-type :display-last))
(def ^:private window-first (c/event-type :window-first))
(def ^:private window-last (c/event-type :window-last))
(def ^:private user-first (c/event-type :user))

(defn type-of
  "The type of the SDL_Event at pointer `p`: a keyword, or the integer when SDL
  has no name for it."
  [p]
  (core/unenum c/event-type-names (ffi/read p :uint32)))

(defn decode
  "The SDL_Event at pointer `p` as a map (see the namespace doc)."
  [p]
  (let [t (ffi/read p :uint32)
        kw (get c/event-type-names t t)
        [layout post] (or (get decoders kw)
                          (cond
                            (<= window-first t window-last) [events/window-event identity]
                            (<= display-first t display-last) [events/display-event identity]
                            (>= t user-first) [events/user-event identity]
                            :else [events/common-event identity]))]
    (-> (ffi/read p layout)
        (dissoc :reserved)
        (assoc :type kw)
        post)))

;; ---------------------------------------------------------------------------
;; the queue
;; ---------------------------------------------------------------------------

(def ^:private scratch (ffi/alloc event-size))

(defn poll!
  "SDL_PollEvent: the next pending event as a map, or nil when the queue is empty.
  Call from the thread that created the window, once per event per frame:

      (loop [] (when-let [e (poll!)] (handle e) (recur)))"
  []
  (when (events/poll-event scratch) (decode scratch)))

(defn poll-all!
  "Drain the queue: every pending event, in order, as a vector."
  []
  (loop [acc (transient [])]
    (if (events/poll-event scratch)
      (recur (conj! acc (decode scratch)))
      (persistent! acc))))

(defn wait!
  "SDL_WaitEvent: block until an event arrives and answer it. Raises on error."
  []
  (core/check-bool "SDL_WaitEvent" (events/wait-event scratch))
  (decode scratch))

(defn wait-timeout!
  "SDL_WaitEventTimeout: the next event within `ms` milliseconds, or nil."
  [ms]
  (when (events/wait-event-timeout scratch (int ms)) (decode scratch)))

(defsdl pump! events/pump-events
  :doc "Gather events from the OS without dequeuing any; poll! does this itself.")

(defn- type-int [t] (core/enum c/event-type t))

(defn has-event? "SDL_HasEvent for a type keyword." [t] (events/has-event (type-int t)))
(defn has-events? "SDL_HasEvents: any event, or any within [min max] types." ([] (events/has-events 0 0xFFFF)) ([min max] (events/has-events (type-int min) (type-int max))))
(defn flush-event! "SDL_FlushEvent: drop every queued event of a type." [t] (events/flush-event (type-int t)) nil)
(defn flush-events! "SDL_FlushEvents: drop every queued event, or those within [min max] types." ([] (events/flush-events 0 0xFFFF) nil) ([min max] (events/flush-events (type-int min) (type-int max)) nil))
(defn set-event-enabled! "SDL_SetEventEnabled: stop or resume queuing a type." [t enabled?] (events/set-event-enabled (type-int t) (boolean enabled?)) nil)
(defn event-enabled? [t] (events/event-enabled (type-int t)))

(defn register-events!
  "SDL_RegisterEvents: reserve `n` consecutive user event types; answers the first,
  an integer usable as :type in push-event!."
  [n]
  (let [t (events/register-events (int n))]
    (when (zero? t) (throw (core/sdl-error "SDL_RegisterEvents")))
    t))

(defn push-event!
  "SDL_PushEvent a user event: {:type :user :code 1 :data1 0 :data2 0 :window-id 0},
  :type being :user or an integer from register-events!. Answers true when it was
  queued, false when an event filter dropped it."
  [{:keys [type code data1 data2 window-id] :or {type :user code 0 data1 0 data2 0 window-id 0}}]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a event-size)]
      (ffi/write p events/user-event
                 {:type (type-int type) :reserved 0 :timestamp 0 :window-id window-id
                  :code (int code) :data1 (or data1 ffi/null) :data2 (or data2 ffi/null)})
      (events/push-event p))))

(defn peep-events
  "SDL_PeepEvents with :peek or :get for up to `n` events of types [min max],
  decoded. :get removes them from the queue."
  ([action n] (peep-events action n :first :last))
  ([action n min max]
   (with-open [a (ffi/confined-arena)]
     (let [buf (ffi/alloc a (* n event-size))
           got (events/peep-events buf (int n) (core/enum c/event-action (case action :peek :peekevent :get :getevent action))
                                   (type-int min) (type-int max))]
       (when (neg? got) (throw (core/sdl-error "SDL_PeepEvents")))
       (mapv (fn [i] (decode (ffi/slice buf (* i event-size)))) (range got))))))
