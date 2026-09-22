(ns sdl3.mouse
  "Mouse state and cursors: SDL_mouse.h. Button sets hold :left :middle :right
  :x1 :x2; positions are floats in window coordinates."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.mouse :as mouse]))

(def ^:private masks
  {:left (c/mouse-button :lmask) :middle (c/mouse-button :mmask) :right (c/mouse-button :rmask)
   :x1 (c/mouse-button :x1mask) :x2 (c/mouse-button :x2mask)})

(defn buttons
  "Decode an SDL_MouseButtonFlags integer into a set of button keywords."
  [v]
  (into #{} (for [[k m] masks :when (pos? (bit-and v m))] k)))

(def ^:private button-names {1 :left 2 :middle 3 :right 4 :x1 5 :x2})

(defn button->keyword [i] (get button-names i i))

(defn- state [f]
  (with-outs [x :float y :float]
    (let [b (f x y)]
      {:x (ffi/read x :float) :y (ffi/read y :float) :buttons (buttons b)})))

(defn mouse-state
  "SDL_GetMouseState: {:x :y :buttons} relative to the focused window, as of the
  last event pump."
  []
  (state mouse/get-mouse-state))

(defn global-mouse-state
  "SDL_GetGlobalMouseState: the same in desktop coordinates, queried from the OS now."
  []
  (state mouse/get-global-mouse-state))

(defn relative-mouse-state
  "SDL_GetRelativeMouseState: :x :y are the motion since the last call."
  []
  (state mouse/get-relative-mouse-state))

(defsdl has-mouse? mouse/has-mouse :pred true)
(defsdl mouse-name-for-id mouse/get-mouse-name-for-id)
(defsdl mouse-focus mouse/get-mouse-focus :nullable true
  :doc "The window with mouse focus, or nil.")

(defn mice
  "SDL_GetMice: the connected mice's ids."
  []
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetMice" (mouse/get-mice n))
          cnt (ffi/read n :int)
          ids (mapv (fn [i] (ffi/read p :uint32 (* 4 i))) (range cnt))]
      (core/free! p)
      ids)))

(defn warp-mouse-in-window!
  "SDL_WarpMouseInWindow: move the cursor to x, y in `win` (nil: the focused window)."
  [win x y]
  (mouse/warp-mouse-in-window (or win ffi/null) (double x) (double y))
  nil)

(defn warp-mouse-global!
  "SDL_WarpMouseGlobal: move the cursor to desktop x, y."
  [x y]
  (core/check-bool "SDL_WarpMouseGlobal" (mouse/warp-mouse-global (double x) (double y)))
  nil)

(defsdl set-window-relative-mouse-mode! mouse/set-window-relative-mouse-mode
  :doc "Hide the cursor and report motion as :xrel/:yrel deltas without bounds (for first-person cameras).")
(defsdl window-relative-mouse-mode? mouse/get-window-relative-mouse-mode :pred true)
(defsdl capture-mouse! mouse/capture-mouse
  :doc "Keep receiving motion and button events while the mouse is outside the window (during a drag).")

;; ---------------------------------------------------------------------------
;; cursors
;; ---------------------------------------------------------------------------

(defsdl show-cursor! mouse/show-cursor)
(defsdl hide-cursor! mouse/hide-cursor)
(defsdl cursor-visible? mouse/cursor-visible :pred true)

(defn create-system-cursor
  "SDL_CreateSystemCursor: `id` from sdl3.consts/system-cursor (:default :text
  :wait :crosshair :pointer :move :ew-resize ...); set it with set-cursor!."
  [id]
  (core/check-ptr "SDL_CreateSystemCursor" (mouse/create-system-cursor (core/enum c/system-cursor id))))

(defn create-color-cursor
  "SDL_CreateColorCursor from a surface, with the hotspot at hot-x, hot-y."
  [surface hot-x hot-y]
  (core/check-ptr "SDL_CreateColorCursor" (mouse/create-color-cursor surface (int hot-x) (int hot-y))))

(defsdl set-cursor! mouse/set-cursor)
(defsdl cursor mouse/get-cursor :nullable true)
(defsdl default-cursor mouse/get-default-cursor)
(defsdl destroy-cursor! mouse/destroy-cursor)
