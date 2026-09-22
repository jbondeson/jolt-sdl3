(ns sdl3.gamepad
  "Gamepads: SDL_gamepad.h. A gamepad is a joystick SDL has a mapping for, with
  its controls named by position — the face buttons are :south :east :west
  :north whatever the labels say (button-label answers :a/:cross/...).

  Buttons: :south :east :west :north :back :guide :start :left-stick
  :right-stick :left-shoulder :right-shoulder :dpad-up :dpad-down :dpad-left
  :dpad-right :misc1 ... :touchpad and the paddles. Axes: :leftx :lefty
  :rightx :righty (-32768..32767) and :left-trigger :right-trigger (0..32767).

  Like joysticks, a gamepad is an instance id before open and a pointer after.
  Events for opened gamepads arrive through sdl3.events as :gamepad-button-down,
  :gamepad-axis-motion, :gamepad-added ... with :button and :axis decoded."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.joystick :as joystick]
            [sdl3.raw.gamepad :as pad]))

(defn- button-int [b] (core/enum c/gamepad-button b))
(defn- axis-int [a] (core/enum c/gamepad-axis a))
(defn- pad-type [v] (core/unenum c/gamepad-type-names v))
(defn- sensor-int [s] (core/enum c/sensor-type s))

(def buttons
  "Every named button keyword, in SDL's order."
  (->> c/gamepad-button (remove (fn [[_ v]] (neg? v))) (remove (fn [[k _]] (= k :count))) (sort-by val) (mapv key)))

(def axes
  "Every named axis keyword, in SDL's order."
  (->> c/gamepad-axis (remove (fn [[_ v]] (neg? v))) (remove (fn [[k _]] (= k :count))) (sort-by val) (mapv key)))

;; ---------------------------------------------------------------------------
;; before opening
;; ---------------------------------------------------------------------------

(defn gamepads "SDL_GetGamepads: the connected gamepads' instance ids." [] (core/with-count pad/get-gamepads))
(defsdl has-gamepad? pad/has-gamepad :pred true)
(defsdl gamepad? pad/is-gamepad :pred true
  :doc "Is joystick instance `id` one SDL has a gamepad mapping for?")
(defsdl update! pad/update-gamepads)
(defsdl set-events-enabled! pad/set-gamepad-events-enabled)
(defsdl events-enabled? pad/gamepad-events-enabled :pred true)

(defn info-for-id
  "What SDL knows of gamepad `id` before it is opened."
  [id]
  {:id id
   :name (pad/get-gamepad-name-for-id id)
   :path (pad/get-gamepad-path-for-id id)
   :player-index (pad/get-gamepad-player-index-for-id id)
   :guid (joystick/call-guid pad/get-gamepad-guid-for-id id)
   :vendor (pad/get-gamepad-vendor-for-id id)
   :product (pad/get-gamepad-product-for-id id)
   :product-version (pad/get-gamepad-product-version-for-id id)
   :type (pad-type (pad/get-gamepad-type-for-id id))
   :real-type (pad-type (pad/get-real-gamepad-type-for-id id))})

(defsdl name-for-id pad/get-gamepad-name-for-id :nullable true)

;; ---------------------------------------------------------------------------
;; mappings
;; ---------------------------------------------------------------------------

(defn add-mapping!
  "SDL_AddGamepadMapping: add or replace a mapping string
  (\"GUID,name,a:b0,b:b1,...\"). Answers :added or :updated."
  [mapping]
  (case (int (pad/add-gamepad-mapping mapping))
    1 :added
    0 :updated
    (throw (core/sdl-error "SDL_AddGamepadMapping"))))

(defn add-mappings-from-file!
  "SDL_AddGamepadMappingsFromFile (a gamecontrollerdb.txt); answers how many were added."
  [path]
  (let [n (pad/add-gamepad-mappings-from-file (str path))]
    (when (neg? n) (throw (core/sdl-error "SDL_AddGamepadMappingsFromFile")))
    n))

(defn add-mappings-from-io!
  "SDL_AddGamepadMappingsFromIO from an sdl3.io stream; closes it when `close?`."
  [s close?]
  (let [n (pad/add-gamepad-mappings-from-io s (boolean close?))]
    (when (neg? n) (throw (core/sdl-error "SDL_AddGamepadMappingsFromIO")))
    n))

(defsdl reload-mappings! pad/reload-gamepad-mappings)

(defn mappings
  "SDL_GetGamepadMappings: every mapping SDL has, as strings."
  []
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetGamepadMappings" (pad/get-gamepad-mappings n))
          ms (core/read-strings p (ffi/read n :int))]
      (core/free! p)
      ms)))

(defn mapping "SDL_GetGamepadMapping of an opened gamepad, or nil." [g] (core/take-string (pad/get-gamepad-mapping g)))
(defn mapping-for-id [id] (core/take-string (pad/get-gamepad-mapping-for-id id)))
(defsdl set-mapping! pad/set-gamepad-mapping
  :doc "Replace the mapping of joystick instance `id` (nil restores the default).")

;; ---------------------------------------------------------------------------
;; opened gamepads
;; ---------------------------------------------------------------------------

(defsdl open pad/open-gamepad
  :doc "Open gamepad `id` for state reads and its events; close! it when done.")
(defsdl close! pad/close-gamepad)
(defsdl from-id pad/get-gamepad-from-id :nullable true)
(defsdl from-player-index pad/get-gamepad-from-player-index :nullable true)
(defsdl connected? pad/gamepad-connected :pred true)
(defsdl id pad/get-gamepad-id)
(defsdl joystick pad/get-gamepad-joystick
  :doc "The SDL_Joystick* under the gamepad, for sdl3.joystick's raw view.")
(defsdl gamepad-name pad/get-gamepad-name :nullable true)
(defsdl path pad/get-gamepad-path :nullable true)
(defsdl serial pad/get-gamepad-serial :nullable true)
(defsdl properties pad/get-gamepad-properties)
(defsdl player-index pad/get-gamepad-player-index)
(defsdl set-player-index! pad/set-gamepad-player-index)
(defsdl vendor pad/get-gamepad-vendor)
(defsdl product pad/get-gamepad-product)
(defsdl product-version pad/get-gamepad-product-version)
(defsdl firmware-version pad/get-gamepad-firmware-version)
(defsdl steam-handle pad/get-gamepad-steam-handle)
(defn gamepad-type "SDL_GetGamepadType: :xboxone :ps5 :nintendo-switch-pro :standard ..." [g] (pad-type (pad/get-gamepad-type g)))
(defn real-type "SDL_GetRealGamepadType: the type ignoring any mapping override." [g] (pad-type (pad/get-real-gamepad-type g)))

(defn connection-state [g]
  (core/unenum c/joystick-connection-state-names (pad/get-gamepad-connection-state g)))

(defn power-info
  "{:state :on-battery|:charging|... :percent 0-100 or nil}."
  [g]
  (with-outs [pct :int]
    (let [st (core/unenum c/power-state-names (pad/get-gamepad-power-info g pct))
          p (ffi/read pct :int)]
      {:state st :percent (when-not (neg? p) p)})))

(defn has-button? [g b] (pad/gamepad-has-button g (button-int b)))
(defn has-axis? [g a] (pad/gamepad-has-axis g (axis-int a)))

(defn button?
  "SDL_GetGamepadButton: is button `b` (a keyword) held?"
  [g b]
  (pad/get-gamepad-button g (button-int b)))

(defn axis
  "SDL_GetGamepadAxis: sticks -32768..32767, triggers 0..32767."
  [g a]
  (pad/get-gamepad-axis g (axis-int a)))

(defn axis-normalized
  "An axis as -1.0..1.0 (sticks) or 0.0..1.0 (triggers)."
  [g a]
  (max -1.0 (/ (double (axis g a)) 32767.0)))

(defn state
  "Every control at once: {:buttons #{held ...} :axes {:leftx n ...}}, over the
  controls this gamepad has."
  [g]
  {:buttons (into #{} (filter #(and (has-button? g %) (button? g %))) buttons)
   :axes (into {} (for [a axes :when (has-axis? g a)] [a (axis g a)]))})

(defn button-label
  "SDL_GetGamepadButtonLabel: what button `b` is printed with on this pad —
  :a :b :x :y, :cross :circle :square :triangle, or :unknown."
  [g b]
  (core/unenum c/gamepad-button-label-names (pad/get-gamepad-button-label g (button-int b))))

(defn button-label-for-type [t b]
  (core/unenum c/gamepad-button-label-names
               (pad/get-gamepad-button-label-for-type (core/enum c/gamepad-type t) (button-int b))))

(defsdl apple-sf-symbols-name-for-button pad/get-gamepad-apple-sf-symbols-name-for-button :nullable true)
(defsdl apple-sf-symbols-name-for-axis pad/get-gamepad-apple-sf-symbols-name-for-axis :nullable true)

;; ---------------------------------------------------------------------------
;; names <-> SDL's mapping strings
;; ---------------------------------------------------------------------------

(defn string-for-button "SDL_GetGamepadStringForButton: :south -> \"a\"." [b] (pad/get-gamepad-string-for-button (button-int b)))
(defn button-from-string [s] (core/unenum c/gamepad-button-names (pad/get-gamepad-button-from-string s)))
(defn string-for-axis [a] (pad/get-gamepad-string-for-axis (axis-int a)))
(defn axis-from-string [s] (core/unenum c/gamepad-axis-names (pad/get-gamepad-axis-from-string s)))
(defn string-for-type [t] (pad/get-gamepad-string-for-type (core/enum c/gamepad-type t)))
(defn type-from-string [s] (pad-type (pad/get-gamepad-type-from-string s)))

;; ---------------------------------------------------------------------------
;; touchpads and sensors
;; ---------------------------------------------------------------------------

(defsdl num-touchpads pad/get-num-gamepad-touchpads)
(defsdl num-touchpad-fingers pad/get-num-gamepad-touchpad-fingers)

(defn touchpad-finger
  "SDL_GetGamepadTouchpadFinger: {:down :x :y :pressure}, x and y 0.0-1.0."
  [g touchpad finger]
  (with-outs [down :bool x :float y :float pressure :float]
    (core/check-bool "SDL_GetGamepadTouchpadFinger" (pad/get-gamepad-touchpad-finger g (int touchpad) (int finger) down x y pressure))
    {:down (ffi/read down :bool) :x (ffi/read x :float) :y (ffi/read y :float) :pressure (ffi/read pressure :float)}))

(defn has-sensor? "`s` is :accel :gyro :accel-l ..." [g s] (pad/gamepad-has-sensor g (sensor-int s)))
(defn sensor-enabled? [g s] (pad/gamepad-sensor-enabled g (sensor-int s)))

(defn set-sensor-enabled! [g s enabled?]
  (core/check-bool "SDL_SetGamepadSensorEnabled" (pad/set-gamepad-sensor-enabled g (sensor-int s) (boolean enabled?)))
  nil)

(defn sensor-data-rate "Samples per second, 0.0 when unknown." [g s] (pad/get-gamepad-sensor-data-rate g (sensor-int s)))

(defn sensor-data
  "SDL_GetGamepadSensorData: the latest `n` values (3 for :accel and :gyro) as a vector."
  ([g s] (sensor-data g s 3))
  ([g s n]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (* 4 n))]
       (core/check-bool "SDL_GetGamepadSensorData" (pad/get-gamepad-sensor-data g (sensor-int s) p (int n)))
       (vec (ffi/read-array p :float n))))))

;; ---------------------------------------------------------------------------
;; output
;; ---------------------------------------------------------------------------

(defn rumble!
  "SDL_RumbleGamepad: low and high frequency motors 0-65535 for `ms`; 0 0 stops."
  [g low high ms]
  (core/check-bool "SDL_RumbleGamepad" (pad/rumble-gamepad g (int low) (int high) (int ms)))
  nil)

(defn rumble-triggers! [g left right ms]
  (core/check-bool "SDL_RumbleGamepadTriggers" (pad/rumble-gamepad-triggers g (int left) (int right) (int ms)))
  nil)

(defn set-led! [g r gr b]
  (core/check-bool "SDL_SetGamepadLED" (pad/set-gamepad-led g (int r) (int gr) (int b)))
  nil)

(defn send-effect! [g bs]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/bytes->ptr a bs)]
      (core/check-bool "SDL_SendGamepadEffect" (pad/send-gamepad-effect g p (int n)))))
  nil)
