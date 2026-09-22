(ns sdl3.haptic
  "Force feedback: SDL_haptic.h. Needs the :haptic subsystem. For simple
  controller rumble, sdl3.gamepad/rumble! is easier; this is the full effect
  API for wheels, joysticks and haptic mice.

  An effect is a map; :type picks the kind and the rest are the C fields:

      {:type :sine :direction {:type :cartesian :dir [1 0 0]}
       :length 1000 :period 100 :magnitude 20000 :attack-length 100 :fade-length 200}

  Kinds: :constant; :sine :square :triangle :sawtoothup :sawtoothdown
  (periodic); :spring :damper :inertia :friction (condition, with 3-axis
  vectors like :right-sat [a b c]); :ramp; :leftright (:large-magnitude
  :small-magnitude); :custom (:channels :period :samples, :data a vector of
  Uint16s). :length and run-effect!'s iterations take :infinity. A direction
  :type is :polar, :cartesian, :spherical or :steering-axis."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.haptic :as haptic]))

(def ^:private infinity (c/haptic :infinity))

(def ^:private feature-bits
  (select-keys c/haptic [:constant :sine :square :triangle :sawtoothup :sawtoothdown :ramp :spring
                         :damper :inertia :friction :leftright :custom :gain :autocenter :status :pause]))

(def ^:private member
  {:constant [haptic/haptic-constant]
   :sine [haptic/haptic-periodic] :square [haptic/haptic-periodic] :triangle [haptic/haptic-periodic]
   :sawtoothup [haptic/haptic-periodic] :sawtoothdown [haptic/haptic-periodic]
   :spring [haptic/haptic-condition] :damper [haptic/haptic-condition]
   :inertia [haptic/haptic-condition] :friction [haptic/haptic-condition]
   :ramp [haptic/haptic-ramp] :leftright [haptic/haptic-left-right] :custom [haptic/haptic-custom]})

(defn- write-effect!
  "Write effect map `m` into a fresh SDL_HapticEffect in `arena`; answers the pointer."
  [arena {:keys [type direction length data] :as m}]
  (let [[layout] (or (member type)
                     (throw (ex-info (str "unknown haptic effect type " type) {:type type :known (sort (keys member))})))
        p (ffi/alloc arena (ffi/layout-size haptic/haptic-effect))
        fields (cond-> (assoc m :type (c/haptic type))
                 direction (assoc :direction (-> direction
                                                 (update :type #(core/enum (select-keys c/haptic [:polar :cartesian :spherical :steering-axis]) (or % :cartesian)))
                                                 (update :dir #(vec (take 3 (concat % [0 0 0]))))))
                 (= :infinity length) (assoc :length infinity)
                 data (assoc :data (let [n (count data)
                                         q (ffi/alloc arena (* 2 (max 1 n)))]
                                     (ffi/write-array q :uint16 (short-array (map unchecked-short data)))
                                     q)))]
    ;; every member of the union starts at offset 0, so the member's own layout
    ;; writes the effect
    (core/write-fields! p layout fields)))

;; ---------------------------------------------------------------------------
;; devices
;; ---------------------------------------------------------------------------

(defn haptics "SDL_GetHaptics: the haptic devices' instance ids." [] (core/with-count haptic/get-haptics))
(defsdl name-for-id haptic/get-haptic-name-for-id :nullable true)
(defsdl open haptic/open-haptic)
(defsdl from-id haptic/get-haptic-from-id :nullable true)
(defsdl mouse-haptic? haptic/is-mouse-haptic :pred true)
(defsdl open-from-mouse haptic/open-haptic-from-mouse)
(defsdl joystick-haptic? haptic/is-joystick-haptic :pred true
  :doc "Does an opened sdl3.joystick joystick (or sdl3.gamepad/joystick of a gamepad) do force feedback?")
(defsdl open-from-joystick haptic/open-haptic-from-joystick)
(defsdl id haptic/get-haptic-id)
(defsdl haptic-name haptic/get-haptic-name :nullable true)

;; stores :custom sample data for the effects still alive on each device
(def ^:private effect-arenas (atom {}))

(defn close!
  "SDL_CloseHaptic, releasing the device's effects."
  [h]
  (haptic/close-haptic h)
  (doseq [[k a] @effect-arenas :when (= h (first k))]
    (swap! effect-arenas dissoc k)
    (ffi/close-arena a))
  nil)

(defn features
  "SDL_GetHapticFeatures as a set: the effect kinds it plays plus :gain
  :autocenter :status and :pause when it supports those controls."
  [h]
  (let [v (haptic/get-haptic-features h)]
    (when (zero? v) (throw (core/sdl-error "SDL_GetHapticFeatures")))
    (into #{} (for [[k b] feature-bits :when (pos? (bit-and v b))] k))))

(defn max-effects [h] (let [n (haptic/get-max-haptic-effects h)] (when (neg? n) (throw (core/sdl-error "SDL_GetMaxHapticEffects"))) n))
(defn max-effects-playing [h] (let [n (haptic/get-max-haptic-effects-playing h)] (when (neg? n) (throw (core/sdl-error "SDL_GetMaxHapticEffectsPlaying"))) n))
(defn num-axes [h] (let [n (haptic/get-num-haptic-axes h)] (when (neg? n) (throw (core/sdl-error "SDL_GetNumHapticAxes"))) n))

;; ---------------------------------------------------------------------------
;; effects
;; ---------------------------------------------------------------------------

(defn effect-supported? [h effect]
  (with-open [a (ffi/confined-arena)]
    (haptic/haptic-effect-supported h (write-effect! a effect))))

(defn create-effect!
  "SDL_CreateHapticEffect: upload `effect` (a map, see the namespace doc) and
  answer its id for run-effect!."
  [h effect]
  (let [a (ffi/shared-arena)
        id (haptic/create-haptic-effect h (write-effect! a effect))]
    (when (neg? id) (ffi/close-arena a) (throw (core/sdl-error "SDL_CreateHapticEffect")))
    (swap! effect-arenas assoc [h id] a)
    id))

(defn update-effect!
  "SDL_UpdateHapticEffect: replace effect `id` with `effect` of the same :type."
  [h id effect]
  (let [a (ffi/shared-arena)]
    (if (haptic/update-haptic-effect h id (write-effect! a effect))
      (let [old (get @effect-arenas [h id])]
        (swap! effect-arenas assoc [h id] a)
        (when old (ffi/close-arena old)))
      (do (ffi/close-arena a) (throw (core/sdl-error "SDL_UpdateHapticEffect")))))
  nil)

(defn run-effect!
  "SDL_RunHapticEffect `iterations` times (default 1; :infinity repeats until stopped)."
  ([h id] (run-effect! h id 1))
  ([h id iterations]
   (core/check-bool "SDL_RunHapticEffect" (haptic/run-haptic-effect h id (if (= :infinity iterations) infinity (int iterations))))
   nil))

(defsdl stop-effect! haptic/stop-haptic-effect)
(defsdl effect-playing? haptic/get-haptic-effect-status :pred true
  :doc "Is effect id playing? Needs the :status feature.")

(defn destroy-effect! [h id]
  (haptic/destroy-haptic-effect h id)
  (when-let [a (get @effect-arenas [h id])]
    (swap! effect-arenas dissoc [h id])
    (ffi/close-arena a))
  nil)

(defn set-gain!
  "SDL_SetHapticGain: 0-100 percent overall strength (needs :gain)."
  [h gain]
  (core/check-bool "SDL_SetHapticGain" (haptic/set-haptic-gain h (int gain)))
  nil)

(defn set-autocenter!
  "SDL_SetHapticAutocenter: 0 (off) to 100 percent (needs :autocenter)."
  [h pct]
  (core/check-bool "SDL_SetHapticAutocenter" (haptic/set-haptic-autocenter h (int pct)))
  nil)

(defsdl pause! haptic/pause-haptic)
(defsdl resume! haptic/resume-haptic)
(defsdl stop-all! haptic/stop-haptic-effects)

;; ---------------------------------------------------------------------------
;; simple rumble
;; ---------------------------------------------------------------------------

(defsdl rumble-supported? haptic/haptic-rumble-supported :pred true)
(defsdl init-rumble! haptic/init-haptic-rumble)
(defsdl stop-rumble! haptic/stop-haptic-rumble)

(defn play-rumble!
  "SDL_PlayHapticRumble at `strength` 0.0-1.0 for `ms` (:infinity allowed); call
  init-rumble! once first."
  [h strength ms]
  (core/check-bool "SDL_PlayHapticRumble"
                   (haptic/play-haptic-rumble h (double strength) (if (= :infinity ms) infinity (int ms))))
  nil)
