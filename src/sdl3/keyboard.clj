(ns sdl3.keyboard
  "Keyboard state, scancodes, keycodes and text input: SDL_keyboard.h.

  A scancode is the physical key (:a is the key at QWERTY A's position) and a
  keycode the symbol it produces under the current layout; both are keywords
  from sdl3.consts/scancode and sdl3.consts/keycode, and both accept the raw
  integer too. Modifiers are sets of :lshift :rshift :lctrl :rctrl :lalt :ralt
  :lgui :rgui :num :caps :mode :scroll; mod? answers the side-agnostic question."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.rect :as rect]
            [sdl3.raw.keyboard :as kb]))

(defn scancode "The integer for a scancode keyword (or integer)." [sc] (core/enum c/scancode sc))
(defn keycode "The integer for a keycode keyword (or integer)." [k] (core/enum c/keycode k))

(def ^:private keycode-names
  (reduce (fn [m [k v]]
            (if (or (contains? m v) (re-find #"mask$" (name k))) m (assoc m v k)))
          {} c/keycode))

(defn scancode->keyword [v] (core/unenum c/scancode-names v))
(defn keycode->keyword [v] (get keycode-names v v))

(defn scancode-name
  "SDL_GetScancodeName: SDL's human-readable name, e.g. \"Space\"."
  [sc]
  (kb/get-scancode-name (scancode sc)))

(defn key-name
  "SDL_GetKeyName, e.g. \"Left Shift\"."
  [k]
  (kb/get-key-name (keycode k)))

(defn scancode-from-name
  "SDL_GetScancodeFromName, as a keyword; nil when unknown."
  [s]
  (let [v (kb/get-scancode-from-name s)]
    (when (pos? v) (scancode->keyword v))))

(defn key-from-name
  "SDL_GetKeyFromName, as a keyword; nil when unknown."
  [s]
  (let [v (kb/get-key-from-name s)]
    (when (pos? v) (keycode->keyword v))))

(defn key-from-scancode
  "SDL_GetKeyFromScancode: the keycode the layout maps `sc` to, with modifiers
  `mods` (a set or integer) applied."
  ([sc] (key-from-scancode sc nil false))
  ([sc mods key-event?]
   (keycode->keyword (kb/get-key-from-scancode (scancode sc) (core/flags c/keymod mods) (boolean key-event?)))))

(defn scancode-from-key
  "SDL_GetScancodeFromKey: [scancode modifiers] producing keycode `k`."
  [k]
  (with-outs [m :uint16]
    (let [sc (kb/get-scancode-from-key (keycode k) m)]
      [(scancode->keyword sc) (core/unflag c/keymod (ffi/read m :uint16))])))

;; ---------------------------------------------------------------------------
;; state
;; ---------------------------------------------------------------------------

(defn key-down?
  "Is the key at scancode `sc` currently held? Reads SDL's keyboard state array,
  which poll! keeps current."
  [sc]
  (ffi/read (kb/get-keyboard-state ffi/null) :bool (scancode sc)))

(defn pressed-scancodes
  "The set of scancode keywords currently held."
  []
  (with-outs [n :int]
    (let [p (kb/get-keyboard-state n)
          cnt (ffi/read n :int)]
      (into #{} (for [i (range cnt) :when (ffi/read p :bool i)] (scancode->keyword i))))))

(defsdl reset-keyboard! kb/reset-keyboard
  :doc "Release every key, as if the user let go of all of them.")

(defn mod-state
  "SDL_GetModState as a set of modifier keywords."
  []
  (core/unflag c/keymod (kb/get-mod-state)))

(defn set-mod-state! [mods]
  (kb/set-mod-state (core/flags c/keymod mods))
  nil)

(defn mod?
  "Is modifier `m` in `mods` (a set from an event's :mod or from mod-state)?
  :ctrl :shift :alt :gui match either side; :lshift and the rest match one."
  [mods m]
  (case m
    :ctrl (boolean (or (mods :lctrl) (mods :rctrl)))
    :shift (boolean (or (mods :lshift) (mods :rshift)))
    :alt (boolean (or (mods :lalt) (mods :ralt)))
    :gui (boolean (or (mods :lgui) (mods :rgui)))
    (contains? mods m)))

(defsdl has-keyboard? kb/has-keyboard :pred true)

(defn keyboards
  "SDL_GetKeyboards: the connected keyboards' ids."
  []
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetKeyboards" (kb/get-keyboards n))
          cnt (ffi/read n :int)
          ids (mapv (fn [i] (ffi/read p :uint32 (* 4 i))) (range cnt))]
      (core/free! p)
      ids)))

(defsdl keyboard-name-for-id kb/get-keyboard-name-for-id)
(defsdl keyboard-focus kb/get-keyboard-focus :nullable true
  :doc "The window with keyboard focus, or nil.")

;; ---------------------------------------------------------------------------
;; text input
;; ---------------------------------------------------------------------------

(defsdl start-text-input! kb/start-text-input
  :doc "Begin delivering :text-input events for `window` (and show the on-screen keyboard where there is one).")
(defsdl stop-text-input! kb/stop-text-input)
(defsdl text-input-active? kb/text-input-active :pred true)
(defsdl clear-composition! kb/clear-composition)
(defsdl start-text-input-with-properties! kb/start-text-input-with-properties)

(defn set-text-input-area!
  "SDL_SetTextInputArea: where the composition UI should appear, an SDL_Rect map,
  and the cursor's offset within it."
  [win r cursor]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_SetTextInputArea" (kb/set-text-input-area win (rect/alloc-rect a r) (int cursor))))
  nil)

(defn text-input-area
  "SDL_GetTextInputArea as [rect cursor]."
  [win]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (with-outs [cur :int]
        (core/check-bool "SDL_GetTextInputArea" (kb/get-text-input-area win r cur))
        [(rect/read-rect r) (ffi/read cur :int)]))))

(defsdl has-screen-keyboard-support? kb/has-screen-keyboard-support :pred true)
(defsdl screen-keyboard-shown? kb/screen-keyboard-shown :pred true)
