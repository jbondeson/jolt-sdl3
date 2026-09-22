(ns sdl3.joystick
  "Joysticks: SDL_joystick.h. The low-level device view — numbered axes, buttons,
  hats and balls. For controllers with a known layout use sdl3.gamepad, which
  names them (:south, :leftx) and works for any mapped controller.

  A joystick is known by its instance id (an integer, stable while connected)
  before it is opened, and by the SDL_Joystick* pointer open answers after.
  Axis values are -32768..32767; axis-normalized maps them to -1.0..1.0.

  attach-virtual! creates a software joystick, which is how the tests drive
  this namespace without hardware and how an app can inject input."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.joystick :as joy]
            [sdl3.raw.guid :as guid]))

;; ---------------------------------------------------------------------------
;; GUIDs
;; ---------------------------------------------------------------------------

(defn guid->string
  "SDL_GUIDToString for the SDL_GUID at pointer `p`: 32 hex digits."
  [p]
  (with-open [a (ffi/confined-arena)]
    (let [buf (ffi/alloc a 33)]
      (guid/guid-to-string p buf 33)
      (ffi/ptr->string buf))))

(defn call-guid
  "Call a raw binding answering SDL_GUID by value (`f` with a leading out
  pointer) and answer the GUID as a string."
  [f & args]
  (with-open [a (ffi/confined-arena)]
    (let [out (ffi/alloc a guid/guid)]
      (apply f out args)
      (guid->string out))))

;; ---------------------------------------------------------------------------
;; before opening
;; ---------------------------------------------------------------------------

(defn joysticks "SDL_GetJoysticks: the connected joysticks' instance ids." [] (core/with-count joy/get-joysticks))
(defsdl has-joystick? joy/has-joystick :pred true)
(defsdl lock! joy/lock-joysticks)
(defsdl unlock! joy/unlock-joysticks)
(defsdl update! joy/update-joysticks
  :doc "Poll joystick state; only needed when joystick events are disabled.")
(defsdl set-events-enabled! joy/set-joystick-events-enabled)
(defsdl events-enabled? joy/joystick-events-enabled :pred true)

(defn- joy-type [v] (core/unenum c/joystick-type-names v))

(defn info-for-id
  "What SDL knows of joystick `id` before it is opened:
  {:id :name :path :player-index :guid :vendor :product :product-version :type :virtual?}."
  [id]
  {:id id
   :name (joy/get-joystick-name-for-id id)
   :path (joy/get-joystick-path-for-id id)
   :player-index (joy/get-joystick-player-index-for-id id)
   :guid (call-guid joy/get-joystick-guid-for-id id)
   :vendor (joy/get-joystick-vendor-for-id id)
   :product (joy/get-joystick-product-for-id id)
   :product-version (joy/get-joystick-product-version-for-id id)
   :type (joy-type (joy/get-joystick-type-for-id id))
   :virtual? (joy/is-joystick-virtual id)})

(defsdl name-for-id joy/get-joystick-name-for-id :nullable true)

;; ---------------------------------------------------------------------------
;; opened joysticks
;; ---------------------------------------------------------------------------

(defsdl open joy/open-joystick
  :doc "Open joystick `id` for state reads and its events; close! it when done.")
(defsdl close! joy/close-joystick)
(defsdl from-id joy/get-joystick-from-id :nullable true
  :doc "The already-opened joystick with instance id `id`, or nil.")
(defsdl from-player-index joy/get-joystick-from-player-index :nullable true)
(defsdl connected? joy/joystick-connected :pred true)
(defsdl id joy/get-joystick-id)
(defsdl joystick-name joy/get-joystick-name :nullable true)
(defsdl path joy/get-joystick-path :nullable true)
(defsdl serial joy/get-joystick-serial :nullable true)
(defsdl properties joy/get-joystick-properties)
(defsdl player-index joy/get-joystick-player-index)
(defsdl set-player-index! joy/set-joystick-player-index)
(defsdl vendor joy/get-joystick-vendor)
(defsdl product joy/get-joystick-product)
(defsdl product-version joy/get-joystick-product-version)
(defsdl firmware-version joy/get-joystick-firmware-version)
(defn guid [j] (call-guid joy/get-joystick-guid j))
(defn joystick-type [j] (joy-type (joy/get-joystick-type j)))
(defn connection-state
  "SDL_GetJoystickConnectionState: :wired, :wireless or :unknown."
  [j]
  (let [v (core/unenum c/joystick-connection-state-names (joy/get-joystick-connection-state j))]
    (when (= v :invalid) (throw (core/sdl-error "SDL_GetJoystickConnectionState")))
    v))

(defn power-info
  "SDL_GetJoystickPowerInfo: {:state :on-battery|:charging|... :percent 0-100 or nil}."
  [j]
  (with-outs [pct :int]
    (let [st (core/unenum c/power-state-names (joy/get-joystick-power-info j pct))
          p (ffi/read pct :int)]
      {:state st :percent (when-not (neg? p) p)})))

(defsdl num-axes joy/get-num-joystick-axes)
(defsdl num-buttons joy/get-num-joystick-buttons)
(defsdl num-hats joy/get-num-joystick-hats)
(defsdl num-balls joy/get-num-joystick-balls)

(defn axis "SDL_GetJoystickAxis: -32768..32767." [j i] (joy/get-joystick-axis j (int i)))
(defn axis-normalized "An axis as -1.0..1.0." [j i] (max -1.0 (/ (double (axis j i)) 32767.0)))

(defn axis-initial-state
  "SDL_GetJoystickAxisInitialState: the axis's value at open, or nil when unknown."
  [j i]
  (with-outs [v :int16]
    (when (joy/get-joystick-axis-initial-state j (int i) v) (ffi/read v :int16))))

(defn button? "SDL_GetJoystickButton." [j i] (joy/get-joystick-button j (int i)))

(def ^:private hat-names (into {} (map (fn [[k v]] [v k])) c/joystick-hat))

(defn hat
  "SDL_GetJoystickHat as :centered :up :right :down :left :rightup :rightdown :leftup or :leftdown."
  [j i]
  (core/unenum hat-names (joy/get-joystick-hat j (int i))))

(defn ball
  "SDL_GetJoystickBall: [dx dy] since the last call."
  [j i]
  (with-outs [dx :int dy :int]
    (core/check-bool "SDL_GetJoystickBall" (joy/get-joystick-ball j (int i) dx dy))
    [(ffi/read dx :int) (ffi/read dy :int)]))

(defn state
  "Every control at once: {:axes [..] :buttons [bool ..] :hats [kw ..]}."
  [j]
  {:axes (mapv #(axis j %) (range (num-axes j)))
   :buttons (mapv #(button? j %) (range (num-buttons j)))
   :hats (mapv #(hat j %) (range (num-hats j)))})

(defn rumble!
  "SDL_RumbleJoystick: low and high frequency motors 0-65535 for `ms`; 0 0 stops."
  [j low high ms]
  (core/check-bool "SDL_RumbleJoystick" (joy/rumble-joystick j (int low) (int high) (int ms)))
  nil)

(defn rumble-triggers! [j left right ms]
  (core/check-bool "SDL_RumbleJoystickTriggers" (joy/rumble-joystick-triggers j (int left) (int right) (int ms)))
  nil)

(defn set-led! [j r g b]
  (core/check-bool "SDL_SetJoystickLED" (joy/set-joystick-led j (int r) (int g) (int b)))
  nil)

(defn send-effect!
  "SDL_SendJoystickEffect: a device-specific effect packet, as a byte-array."
  [j bs]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/bytes->ptr a bs)]
      (core/check-bool "SDL_SendJoystickEffect" (joy/send-joystick-effect j p (int n)))))
  nil)

;; ---------------------------------------------------------------------------
;; virtual joysticks
;; ---------------------------------------------------------------------------

(defn attach-virtual!
  "SDL_AttachVirtualJoystick; answers the new instance id. `desc`:

    :type        a sdl3.consts/joystick-type keyword (:gamepad makes it a
                 gamepad sdl3.gamepad can open, given enough buttons and axes)
    :name        a string
    :naxes :nbuttons :nhats :nballs     counts
    :vendor-id :product-id              integers
    :button-mask :axis-mask             which gamepad controls exist (optional)

  Drive it with the set-virtual-*! functions on the opened joystick."
  [desc]
  (with-open [a (ffi/confined-arena)]
    (let [d (core/alloc-fields
             a joy/virtual-joystick-desc
             (-> desc
                 (select-keys [:naxes :nbuttons :nhats :nballs :vendor-id :product-id :button-mask :axis-mask])
                 (assoc :version (ffi/layout-size joy/virtual-joystick-desc)
                        :type (core/enum c/joystick-type (:type desc :gamepad)))
                 (cond-> (:name desc) (assoc :name (ffi/string->ptr a (:name desc))))))
          id (joy/attach-virtual-joystick d)]
      (when (zero? id) (throw (core/sdl-error "SDL_AttachVirtualJoystick")))
      id)))

(defsdl detach-virtual! joy/detach-virtual-joystick)
(defsdl virtual? joy/is-joystick-virtual :pred true)

(defn set-virtual-axis! [j i v]
  (core/check-bool "SDL_SetJoystickVirtualAxis" (joy/set-joystick-virtual-axis j (int i) (int v)))
  nil)

(defn set-virtual-button! [j i down?]
  (core/check-bool "SDL_SetJoystickVirtualButton" (joy/set-joystick-virtual-button j (int i) (boolean down?)))
  nil)

(defn set-virtual-hat!
  "Set hat `i` to a keyword (:up, :leftdown ...) or the raw value."
  [j i v]
  (core/check-bool "SDL_SetJoystickVirtualHat" (joy/set-joystick-virtual-hat j (int i) (core/enum c/joystick-hat v)))
  nil)

(defn set-virtual-ball! [j i dx dy]
  (core/check-bool "SDL_SetJoystickVirtualBall" (joy/set-joystick-virtual-ball j (int i) (int dx) (int dy)))
  nil)
