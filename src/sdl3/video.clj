(ns sdl3.video
  "Windows, displays and OpenGL contexts: SDL_video.h.

  A window is the SDL_Window* pointer SDL handed out; destroy it with
  destroy-window!. Sizes and positions come back as [w h] / [x y] vectors, flags
  as sets of keywords from sdl3.consts/window-flags."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.rect :as rect]
            [sdl3.raw.video :as video]))

;; ---------------------------------------------------------------------------
;; windows
;; ---------------------------------------------------------------------------

(defn create-window
  "SDL_CreateWindow. `flags`: keywords from sdl3.consts/window-flags (:resizable
  :fullscreen :borderless :hidden :high-pixel-density :always-on-top :opengl
  :metal :vulkan ...), a collection of them, or an integer."
  ([title w h] (create-window title w h 0))
  ([title w h flags]
   (core/check-ptr "SDL_CreateWindow"
                   (video/create-window (str title) (int w) (int h) (core/flags c/window-flags flags)))))

(defn create-popup-window
  "SDL_CreatePopupWindow: a tooltip or popup-menu child of `parent`, placed at an
  offset from it. `flags` must include :tooltip or :popup-menu."
  [parent offset-x offset-y w h flags]
  (core/check-ptr "SDL_CreatePopupWindow"
                  (video/create-popup-window parent (int offset-x) (int offset-y) (int w) (int h)
                                             (core/flags c/window-flags flags))))

(defsdl create-window-with-properties video/create-window-with-properties)
(defsdl destroy-window! video/destroy-window)

(defn- pos [v] (if (keyword? v) (core/enum c/windowpos v) (int v)))

(defn set-window-position!
  "SDL_SetWindowPosition; x and y may be :centered or :undefined."
  [win x y]
  (core/check-bool "SDL_SetWindowPosition" (video/set-window-position win (pos x) (pos y)))
  nil)

(defn- int-pair [what f win]
  (with-outs [a :int b :int]
    (core/check-bool what (f win a b))
    [(ffi/read a :int) (ffi/read b :int)]))

(defn window-position "SDL_GetWindowPosition as [x y]." [win] (int-pair "SDL_GetWindowPosition" video/get-window-position win))
(defn window-size "SDL_GetWindowSize as [w h], in window coordinates." [win] (int-pair "SDL_GetWindowSize" video/get-window-size win))
(defn window-size-in-pixels "SDL_GetWindowSizeInPixels as [w h] — the drawable size, larger than window-size on high-DPI displays." [win] (int-pair "SDL_GetWindowSizeInPixels" video/get-window-size-in-pixels win))
(defn window-minimum-size [win] (int-pair "SDL_GetWindowMinimumSize" video/get-window-minimum-size win))
(defn window-maximum-size [win] (int-pair "SDL_GetWindowMaximumSize" video/get-window-maximum-size win))

(defsdl set-window-size! video/set-window-size)
(defsdl set-window-minimum-size! video/set-window-minimum-size)
(defsdl set-window-maximum-size! video/set-window-maximum-size)
(defsdl set-window-title! video/set-window-title)
(defsdl window-title video/get-window-title)
(defsdl show-window! video/show-window)
(defsdl hide-window! video/hide-window)
(defsdl raise-window! video/raise-window)
(defsdl maximize-window! video/maximize-window)
(defsdl minimize-window! video/minimize-window)
(defsdl restore-window! video/restore-window)
(defsdl set-window-fullscreen! video/set-window-fullscreen)
(defsdl set-window-resizable! video/set-window-resizable)
(defsdl set-window-bordered! video/set-window-bordered)
(defsdl set-window-always-on-top! video/set-window-always-on-top)
(defsdl set-window-focusable! video/set-window-focusable)
(defsdl set-window-mouse-grab! video/set-window-mouse-grab)
(defsdl window-mouse-grab? video/get-window-mouse-grab :pred true)
(defsdl set-window-keyboard-grab! video/set-window-keyboard-grab)
(defsdl window-keyboard-grab? video/get-window-keyboard-grab :pred true)
(defsdl set-window-modal! video/set-window-modal)
(defsdl set-window-parent! video/set-window-parent)
(defsdl window-parent video/get-window-parent :nullable true)
(defsdl sync-window! video/sync-window
  :doc "Block until pending window state changes (size, position, fullscreen) have been applied.")
(defsdl window-id video/get-window-id)
(defsdl window-from-id video/get-window-from-id :nullable true)
(defsdl window-display-scale video/get-window-display-scale)
(defsdl window-pixel-density video/get-window-pixel-density)
(defsdl window-properties video/get-window-properties)
(defsdl window-surface video/get-window-surface)
(defsdl window-has-surface? video/window-has-surface :pred true)
(defsdl update-window-surface! video/update-window-surface)
(defsdl destroy-window-surface! video/destroy-window-surface)
(defsdl set-window-icon! video/set-window-icon)
(defsdl set-window-shape! video/set-window-shape)

(defn set-window-opacity!
  "SDL_SetWindowOpacity: 0.0 transparent to 1.0 opaque."
  [win opacity]
  (core/check-bool "SDL_SetWindowOpacity" (video/set-window-opacity win (double opacity)))
  nil)

(defsdl window-opacity video/get-window-opacity)

(defn window-flags
  "SDL_GetWindowFlags as a set of sdl3.consts/window-flags keywords."
  [win]
  (core/unflag c/window-flags (video/get-window-flags win)))

(defn window-pixel-format
  "SDL_GetWindowPixelFormat as a sdl3.consts/pixel-format keyword."
  [win]
  (core/unenum c/pixel-format-names (video/get-window-pixel-format win)))

(defn flash-window!
  "SDL_FlashWindow: `op` is :cancel, :briefly or :until-focused."
  [win op]
  (core/check-bool "SDL_FlashWindow" (video/flash-window win (core/enum c/flash-operation op)))
  nil)

(defn window-safe-area
  "SDL_GetWindowSafeArea: the rect not covered by notches or system UI."
  [win]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool "SDL_GetWindowSafeArea" (video/get-window-safe-area win r))
      (rect/read-rect r))))

(defn set-window-aspect-ratio!
  "SDL_SetWindowAspectRatio; 0 for either means unconstrained."
  [win min-aspect max-aspect]
  (core/check-bool "SDL_SetWindowAspectRatio" (video/set-window-aspect-ratio win (double min-aspect) (double max-aspect)))
  nil)

(defn window-aspect-ratio [win]
  (with-outs [a :float b :float]
    (core/check-bool "SDL_GetWindowAspectRatio" (video/get-window-aspect-ratio win a b))
    [(ffi/read a :float) (ffi/read b :float)]))

(defn windows
  "SDL_GetWindows: every window SDL currently has, as a vector of pointers."
  []
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetWindows" (video/get-windows n))
          cnt (ffi/read n :int)
          ws (mapv (fn [i] (ffi/read p :pointer (* i (ffi/sizeof :pointer)))) (range cnt))]
      (core/free! p)
      ws)))

;; ---------------------------------------------------------------------------
;; displays
;; ---------------------------------------------------------------------------

(defn displays
  "SDL_GetDisplays: the connected displays' ids, primary first."
  []
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetDisplays" (video/get-displays n))
          cnt (ffi/read n :int)
          ids (mapv (fn [i] (ffi/read p :uint32 (* 4 i))) (range cnt))]
      (core/free! p)
      ids)))

(defsdl primary-display video/get-primary-display)
(defsdl display-name video/get-display-name)
(defsdl display-content-scale video/get-display-content-scale)
(defsdl display-for-window video/get-display-for-window)
(defsdl display-properties video/get-display-properties)

(defn- display-rect [what f id]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool what (f id r))
      (rect/read-rect r))))

(defn display-bounds "SDL_GetDisplayBounds as a rect map." [id] (display-rect "SDL_GetDisplayBounds" video/get-display-bounds id))
(defn display-usable-bounds "SDL_GetDisplayUsableBounds: the bounds minus docks and menu bars." [id] (display-rect "SDL_GetDisplayUsableBounds" video/get-display-usable-bounds id))

(defn- decode-mode [p]
  (-> (ffi/read p video/display-mode)
      (update :format #(core/unenum c/pixel-format-names %))
      (dissoc :internal)))

(defn current-display-mode
  "SDL_GetCurrentDisplayMode as a map: :display-id :format :w :h :pixel-density
  :refresh-rate :refresh-rate-numerator :refresh-rate-denominator."
  [id]
  (decode-mode (core/check-ptr "SDL_GetCurrentDisplayMode" (video/get-current-display-mode id))))

(defn desktop-display-mode
  "SDL_GetDesktopDisplayMode: the mode the desktop runs at, whatever a fullscreen window changed."
  [id]
  (decode-mode (core/check-ptr "SDL_GetDesktopDisplayMode" (video/get-desktop-display-mode id))))

(defn fullscreen-display-modes
  "SDL_GetFullscreenDisplayModes: every fullscreen mode of display `id`."
  [id]
  (with-outs [n :int]
    (let [p (core/check-ptr "SDL_GetFullscreenDisplayModes" (video/get-fullscreen-display-modes id n))
          cnt (ffi/read n :int)
          modes (mapv (fn [i] (decode-mode (ffi/read p :pointer (* i (ffi/sizeof :pointer))))) (range cnt))]
      (core/free! p)
      modes)))

(defn display-orientation
  "SDL_GetCurrentDisplayOrientation as a keyword: :unknown :landscape
  :landscape-flipped :portrait :portrait-flipped."
  [id]
  (core/unenum c/display-orientation-names (video/get-current-display-orientation id)))

(defn natural-display-orientation [id]
  (core/unenum c/display-orientation-names (video/get-natural-display-orientation id)))

(defn display-for-point
  "SDL_GetDisplayForPoint: the display containing point {:x :y} or [x y]."
  [pt]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a rect/point)]
      (rect/write-point! p pt)
      (video/get-display-for-point p))))

(defn system-theme
  "SDL_GetSystemTheme: :unknown, :light or :dark."
  []
  (core/unenum c/system-theme-names (video/get-system-theme)))

(defsdl screen-saver-enabled? video/screen-saver-enabled :pred true)
(defsdl enable-screen-saver! video/enable-screen-saver)
(defsdl disable-screen-saver! video/disable-screen-saver)

(defn video-drivers
  "SDL_GetVideoDriver for every built-in driver, as a vector of names."
  []
  (mapv video/get-video-driver (range (video/get-num-video-drivers))))

(defsdl current-video-driver video/get-current-video-driver)

;; ---------------------------------------------------------------------------
;; OpenGL
;; ---------------------------------------------------------------------------

(defn gl-set-attribute!
  "SDL_GL_SetAttribute; `attr` is a keyword from sdl3.consts/gl-attr
  (:context-major-version, :doublebuffer, ...). Call before creating the window."
  [attr value]
  (core/check-bool "SDL_GL_SetAttribute" (video/gl-set-attribute (core/enum c/gl-attr attr) (int value)))
  nil)

(defn gl-attribute [attr]
  (with-outs [v :int]
    (core/check-bool "SDL_GL_GetAttribute" (video/gl-get-attribute (core/enum c/gl-attr attr) v))
    (ffi/read v :int)))

(defsdl gl-load-library! video/gl-load-library)
(defsdl gl-unload-library! video/gl-unload-library)
(defsdl gl-get-proc-address video/gl-get-proc-address :nullable true)
(defsdl gl-extension-supported? video/gl-extension-supported :pred true)
(defsdl gl-create-context video/gl-create-context)
(defsdl gl-make-current! video/gl-make-current)
(defsdl gl-current-window video/gl-get-current-window :nullable true)
(defsdl gl-current-context video/gl-get-current-context :nullable true)
(defsdl gl-set-swap-interval! video/gl-set-swap-interval)
(defsdl gl-swap-window! video/gl-swap-window)
(defsdl gl-destroy-context! video/gl-destroy-context)

(defn gl-swap-interval [] 
  (with-outs [v :int]
    (core/check-bool "SDL_GL_GetSwapInterval" (video/gl-get-swap-interval v))
    (ffi/read v :int)))
