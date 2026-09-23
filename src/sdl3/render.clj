(ns sdl3.render
  "The 2D accelerated renderer: SDL_render.h.

  Rects, points and colors are Clojure maps or vectors; the drawing calls copy
  them into a scratch cell per call, so a frame of a few hundred rects costs no
  allocation on the jolt side. A rect argument also accepts an SDL_FRect pointer
  (from sdl3.rect/alloc-frect) or nil where SDL allows NULL, and the plural
  calls (fill-rects!, draw-lines!) take either a seq of rects/points or the
  [pointer count] pair sdl3.rect/frects builds once.

      (let [[win ren] (create-window-and-renderer \"hi\" 640 480 [:resizable])]
        (set-draw-color! ren 20 30 60)
        (clear! ren)
        (set-draw-color! ren [220 80 40])
        (fill-rect! ren {:x 40 :y 40 :w 100 :h 60})
        (present! ren))"
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.rect :as rect]
            [sdl3.raw.render :as render]
            [sdl3.raw.blendmode :as blendmode]))

;; ---------------------------------------------------------------------------
;; renderers
;; ---------------------------------------------------------------------------

(defn create-renderer
  "SDL_CreateRenderer for `win`; `driver` names one of (render-drivers) or is nil
  for the best available."
  ([win] (create-renderer win nil))
  ([win driver] (core/check-ptr "SDL_CreateRenderer" (render/create-renderer win driver))))

(defn create-window-and-renderer
  "SDL_CreateWindowAndRenderer: answers [window renderer]. `flags` as for
  sdl3.video/create-window."
  ([title w h] (create-window-and-renderer title w h 0))
  ([title w h flags]
   (with-outs [pw :pointer pr :pointer]
     (core/check-bool "SDL_CreateWindowAndRenderer"
                      (render/create-window-and-renderer (str title) (int w) (int h)
                                                         (core/flags c/window-flags flags) pw pr))
     [(ffi/read pw :pointer) (ffi/read pr :pointer)])))

(defsdl create-software-renderer render/create-software-renderer)
(defsdl create-renderer-with-properties render/create-renderer-with-properties)
(defsdl destroy-renderer! render/destroy-renderer)
(defsdl renderer render/get-renderer :nullable true
  :doc "The renderer of a window, or nil.")
(defsdl render-window render/get-render-window)
(defsdl renderer-name render/get-renderer-name)
(defsdl renderer-properties render/get-renderer-properties)
(defsdl flush! render/flush-renderer)

(defn render-drivers
  "The built-in render driver names, e.g. [\"metal\" \"opengl\" \"software\"]."
  []
  (mapv render/get-render-driver (range (render/get-num-render-drivers))))

(defn- int-pair [what f ren]
  (with-outs [a :int b :int]
    (core/check-bool what (f ren a b))
    [(ffi/read a :int) (ffi/read b :int)]))

(defn output-size "SDL_GetRenderOutputSize as [w h] in pixels." [ren] (int-pair "SDL_GetRenderOutputSize" render/get-render-output-size ren))
(defn current-output-size "SDL_GetCurrentRenderOutputSize: the target's size (a render target or the window)." [ren] (int-pair "SDL_GetCurrentRenderOutputSize" render/get-current-render-output-size ren))

;; ---------------------------------------------------------------------------
;; scratch cells: one call at a time, copied before SDL returns
;; ---------------------------------------------------------------------------

(def ^:private cell-a (ffi/alloc 16))
(def ^:private cell-b (ffi/alloc 16))
(def ^:private cell-c (ffi/alloc 16))
(def ^:private cell-d (ffi/alloc 16))

(defn- frect-arg [cell r]
  (cond (nil? r) ffi/null
        (integer? r) r
        :else (rect/write-frect! cell r)))

(defn- rect-arg [cell r]
  (cond (nil? r) ffi/null
        (integer? r) r
        :else (rect/write-rect! cell r)))

(defn- fpoint-arg [cell p]
  (cond (nil? p) ffi/null
        (integer? p) p
        :else (rect/write-fpoint! cell p)))

(defmacro ^:private with-array
  "Bind [p n] to the [pointer count] pair `items` already is, or build one in a
  confined arena from a seq with `builder`."
  [[p n] builder items & body]
  `(let [items# ~items]
     (if (and (vector? items#) (= 2 (count items#)) (integer? (first items#)) (integer? (second items#)))
       (let [[~p ~n] items#] ~@body)
       (with-open [arena# (ffi/confined-arena)]
         (let [[~p ~n] (~builder arena# items#)] ~@body)))))

;; ---------------------------------------------------------------------------
;; colors
;; ---------------------------------------------------------------------------

(defn- rgba [color]
  (if (map? color)
    [(:r color) (:g color) (:b color) (:a color 255)]
    (let [[r g b a] color] [r g b (or a 255)])))

(defn set-draw-color!
  "SDL_SetRenderDrawColor. Components are 0-255; a missing alpha is 255. Also
  takes one color as {:r :g :b :a} or [r g b a]."
  ([ren color] (let [[r g b a] (rgba color)] (set-draw-color! ren r g b a)))
  ([ren r g b] (set-draw-color! ren r g b 255))
  ([ren r g b a]
   (core/check-bool "SDL_SetRenderDrawColor" (render/set-render-draw-color ren (int r) (int g) (int b) (int a)))
   nil))

(defn set-draw-color-float!
  "SDL_SetRenderDrawColorFloat: components 0.0-1.0 (HDR values may exceed 1.0)."
  ([ren r g b] (set-draw-color-float! ren r g b 1.0))
  ([ren r g b a]
   (core/check-bool "SDL_SetRenderDrawColorFloat" (render/set-render-draw-color-float ren (double r) (double g) (double b) (double a)))
   nil))

(defn draw-color
  "SDL_GetRenderDrawColor as {:r :g :b :a}."
  [ren]
  (with-outs [r :uint8 g :uint8 b :uint8 a :uint8]
    (core/check-bool "SDL_GetRenderDrawColor" (render/get-render-draw-color ren r g b a))
    {:r (ffi/read r :uint8) :g (ffi/read g :uint8) :b (ffi/read b :uint8) :a (ffi/read a :uint8)}))

(defn set-draw-blend-mode!
  "SDL_SetRenderDrawBlendMode: :none :blend :blend-premultiplied :add :add-premultiplied :mod :mul."
  [ren mode]
  (core/check-bool "SDL_SetRenderDrawBlendMode" (render/set-render-draw-blend-mode ren (core/flags c/blend-mode mode)))
  nil)

(defn draw-blend-mode [ren]
  (with-outs [m :uint32]
    (core/check-bool "SDL_GetRenderDrawBlendMode" (render/get-render-draw-blend-mode ren m))
    (let [v (ffi/read m :uint32)] (or (some (fn [[k b]] (when (= b v) k)) c/blend-mode) v))))

(defn set-color-scale!
  "SDL_SetRenderColorScale: multiply every drawn color, for HDR headroom."
  [ren scale]
  (core/check-bool "SDL_SetRenderColorScale" (render/set-render-color-scale ren (double scale)))
  nil)

(defn color-scale [ren]
  (with-outs [s :float]
    (core/check-bool "SDL_GetRenderColorScale" (render/get-render-color-scale ren s))
    (ffi/read s :float)))

;; ---------------------------------------------------------------------------
;; drawing
;; ---------------------------------------------------------------------------

(defsdl clear! render/render-clear
  :doc "Fill the whole target with the draw color.")
(defsdl present! render/render-present
  :doc "Show the frame drawn since the last present!.")

(defn fill-rect!
  "SDL_RenderFillRect. `r` is a rect map, [x y w h], an SDL_FRect pointer, or nil
  for the whole target."
  ([ren r] (core/check-bool "SDL_RenderFillRect" (render/render-fill-rect ren (frect-arg cell-a r))) nil)
  ([ren x y w h] (fill-rect! ren [x y w h])))

(defn draw-rect!
  "SDL_RenderRect: the outline of a rect (as fill-rect! takes it)."
  ([ren r] (core/check-bool "SDL_RenderRect" (render/render-rect ren (frect-arg cell-a r))) nil)
  ([ren x y w h] (draw-rect! ren [x y w h])))

(defn fill-rects!
  "SDL_RenderFillRects: `rs` is a seq of rects or a [pointer count] pair from sdl3.rect/frects."
  [ren rs]
  (with-array [p n] rect/frects rs
    (core/check-bool "SDL_RenderFillRects" (render/render-fill-rects ren p n)))
  nil)

(defn draw-rects!
  "SDL_RenderRects, as fill-rects! takes them."
  [ren rs]
  (with-array [p n] rect/frects rs
    (core/check-bool "SDL_RenderRects" (render/render-rects ren p n)))
  nil)

(defn draw-line!
  "SDL_RenderLine."
  [ren x1 y1 x2 y2]
  (core/check-bool "SDL_RenderLine" (render/render-line ren (double x1) (double y1) (double x2) (double y2)))
  nil)

(defn draw-lines!
  "SDL_RenderLines: connect the points {:x :y} / [x y] in order (or a [pointer count] from sdl3.rect/fpoints)."
  [ren pts]
  (with-array [p n] rect/fpoints pts
    (core/check-bool "SDL_RenderLines" (render/render-lines ren p n)))
  nil)

(defn draw-point!
  "SDL_RenderPoint."
  [ren x y]
  (core/check-bool "SDL_RenderPoint" (render/render-point ren (double x) (double y)))
  nil)

(defn draw-points!
  "SDL_RenderPoints, as draw-lines! takes them."
  [ren pts]
  (with-array [p n] rect/fpoints pts
    (core/check-bool "SDL_RenderPoints" (render/render-points ren p n)))
  nil)

(def debug-text-font-character-size
  "SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE: debug-text! glyphs are this many pixels square."
  8)

(defn debug-text!
  "SDL_RenderDebugText: `text` in SDL's built-in 8x8 font at x, y."
  [ren x y text]
  (core/check-bool "SDL_RenderDebugText" (render/render-debug-text ren (double x) (double y) (str text)))
  nil)

;; ---------------------------------------------------------------------------
;; textures
;; ---------------------------------------------------------------------------

(defn create-texture
  "SDL_CreateTexture: `format` from sdl3.consts/pixel-format (:rgba8888, :abgr8888,
  ...), `access` :static, :streaming or :target."
  [ren format access w h]
  (core/check-ptr "SDL_CreateTexture"
                  (render/create-texture ren (core/enum c/pixel-format format) (core/enum c/texture-access access) (int w) (int h))))

(defsdl create-texture-from-surface render/create-texture-from-surface)
(defsdl create-texture-with-properties render/create-texture-with-properties)
(defsdl destroy-texture! render/destroy-texture)
(defsdl texture-properties render/get-texture-properties)
(defsdl renderer-from-texture render/get-renderer-from-texture)

(defn texture-size
  "SDL_GetTextureSize as [w h] (floats)."
  [tex]
  (with-outs [w :float h :float]
    (core/check-bool "SDL_GetTextureSize" (render/get-texture-size tex w h))
    [(ffi/read w :float) (ffi/read h :float)]))

(defn update-texture!
  "SDL_UpdateTexture: copy `pixels` (a pointer, `pitch` bytes per row) into
  `rect` (an SDL_Rect map/pointer, or nil for the whole texture)."
  [tex rect pixels pitch]
  (core/check-bool "SDL_UpdateTexture" (render/update-texture tex (rect-arg cell-a rect) pixels (int pitch)))
  nil)

(defn lock-texture
  "SDL_LockTexture on a :streaming texture: answers [pixels-pointer pitch] to write
  into; call unlock-texture! after."
  [tex rect]
  (with-outs [pp :pointer pitch :int]
    (core/check-bool "SDL_LockTexture" (render/lock-texture tex (rect-arg cell-a rect) pp pitch))
    [(ffi/read pp :pointer) (ffi/read pitch :int)]))

(defsdl unlock-texture! render/unlock-texture)

(defn lock-texture-to-surface
  "SDL_LockTextureToSurface on a :streaming texture: a temporary SDL_Surface over
  the locked `rect` (an SDL_Rect map, or nil for the whole texture), to draw into
  with sdl3.surface. The pixels are write-only: fill every one. unlock-texture!
  uploads them and frees the surface."
  [tex rect]
  (with-outs [ps :pointer]
    (core/check-bool "SDL_LockTextureToSurface" (render/lock-texture-to-surface tex (rect-arg cell-a rect) ps))
    (ffi/read ps :pointer)))

(defn set-texture-blend-mode! [tex mode]
  (core/check-bool "SDL_SetTextureBlendMode" (render/set-texture-blend-mode tex (core/flags c/blend-mode mode)))
  nil)

(defn set-texture-scale-mode!
  "SDL_SetTextureScaleMode: :nearest, :linear or :pixelart."
  [tex mode]
  (core/check-bool "SDL_SetTextureScaleMode" (render/set-texture-scale-mode tex (core/enum c/scale-mode mode)))
  nil)

(defn texture-scale-mode
  "SDL_GetTextureScaleMode: :nearest, :linear or :pixelart."
  [tex]
  (with-outs [m :int]
    (core/check-bool "SDL_GetTextureScaleMode" (render/get-texture-scale-mode tex m))
    (core/unenum c/scale-mode-names (ffi/read m :int))))

(defn set-default-texture-scale-mode!
  "SDL_SetDefaultTextureScaleMode: the scale mode textures `ren` creates from
  now on start with (:nearest, :linear or :pixelart); textures already made
  keep theirs. Pixel-art games usually want :nearest."
  [ren mode]
  (core/check-bool "SDL_SetDefaultTextureScaleMode"
                   (render/set-default-texture-scale-mode ren (core/enum c/scale-mode mode)))
  nil)

(defn default-texture-scale-mode
  "SDL_GetDefaultTextureScaleMode: the scale mode new textures start with."
  [ren]
  (with-outs [m :int]
    (core/check-bool "SDL_GetDefaultTextureScaleMode" (render/get-default-texture-scale-mode ren m))
    (core/unenum c/scale-mode-names (ffi/read m :int))))

(defn set-texture-color-mod! [tex r g b]
  (core/check-bool "SDL_SetTextureColorMod" (render/set-texture-color-mod tex (int r) (int g) (int b)))
  nil)

(defn set-texture-color-mod-float!
  "SDL_SetTextureColorModFloat: multiply the texture's red, green and blue by
  0.0-1.0 (1.0 leaves a channel alone, 0.0 shuts it off) when drawing it."
  [tex r g b]
  (core/check-bool "SDL_SetTextureColorModFloat" (render/set-texture-color-mod-float tex (double r) (double g) (double b)))
  nil)

(defn set-texture-alpha-mod! [tex a]
  (core/check-bool "SDL_SetTextureAlphaMod" (render/set-texture-alpha-mod tex (int a)))
  nil)

(defn render-texture!
  "SDL_RenderTexture: draw `tex` (or the part `src`, an SDL_FRect map/pointer or
  nil for all of it) into `dst` (nil for the whole target)."
  ([ren tex] (render-texture! ren tex nil nil))
  ([ren tex src dst]
   (core/check-bool "SDL_RenderTexture" (render/render-texture ren tex (frect-arg cell-a src) (frect-arg cell-b dst)))
   nil))

(defn render-texture-rotated!
  "SDL_RenderTextureRotated: as render-texture!, rotated `angle` degrees clockwise
  around `center` ({:x :y} within dst, or nil for its middle) and flipped by
  `flip` (:none, :horizontal, :vertical, or both)."
  [ren tex src dst angle center flip]
  (core/check-bool "SDL_RenderTextureRotated"
                   (render/render-texture-rotated ren tex (frect-arg cell-a src) (frect-arg cell-b dst)
                                                  (double angle) (fpoint-arg cell-c center)
                                                  (core/flags c/flip-mode flip)))
  nil)

(defn render-texture-affine!
  "SDL_RenderTextureAffine: draw `tex` (or the part `src`, an SDL_FRect map or nil)
  as the parallelogram whose top-left corner lands on point `origin`, top-right
  on `right` and bottom-left on `down` ({:x :y} maps or [x y]; nil for `right` or
  `down` uses the source size along that edge)."
  [ren tex src origin right down]
  (core/check-bool "SDL_RenderTextureAffine"
                   (render/render-texture-affine ren tex (frect-arg cell-a src) (fpoint-arg cell-b origin)
                                                 (fpoint-arg cell-c right) (fpoint-arg cell-d down)))
  nil)

(defn render-texture-tiled!
  "SDL_RenderTextureTiled: tile `src` at `scale` across `dst`."
  [ren tex src scale dst]
  (core/check-bool "SDL_RenderTextureTiled"
                   (render/render-texture-tiled ren tex (frect-arg cell-a src) (double scale) (frect-arg cell-b dst)))
  nil)

(defn render-texture-9-grid!
  "SDL_RenderTexture9Grid: a 9-slice of `src` with the given border widths, into `dst`."
  [ren tex src left right top bottom scale dst]
  (core/check-bool "SDL_RenderTexture9Grid"
                   (render/render-texture-9-grid ren tex (frect-arg cell-a src)
                                                 (double left) (double right) (double top) (double bottom)
                                                 (double scale) (frect-arg cell-b dst)))
  nil)

(defn render-geometry!
  "SDL_RenderGeometry: draw triangles from `vertices`, each
  {:position [x y] :color [r g b a] :tex-coord [u v]} (color components 0.0-1.0,
  tex-coord 0.0-1.0 into `tex`, which may be nil), optionally indexed by `indices`."
  ([ren tex vertices] (render-geometry! ren tex vertices nil))
  ([ren tex vertices indices]
   (with-open [a (ffi/confined-arena)]
     (let [n (count vertices)
           size (ffi/layout-size render/vertex)
           vp (ffi/alloc a (* (max n 1) size))]
       (doseq [[i v] (map-indexed vector vertices)]
         (let [[px py] (if (map? (:position v)) [(:x (:position v)) (:y (:position v))] (:position v))
               [r g b al] (if (map? (:color v)) [(:r (:color v)) (:g (:color v)) (:b (:color v)) (:a (:color v) 1.0)] (:color v))
               [u t] (or (if (map? (:tex-coord v)) [(:x (:tex-coord v)) (:y (:tex-coord v))] (:tex-coord v)) [0.0 0.0])]
           (ffi/write (ffi/slice vp (* i size)) render/vertex
                      {:position {:x (double px) :y (double py)}
                       :color {:r (double r) :g (double g) :b (double b) :a (double (or al 1.0))}
                       :tex-coord {:x (double u) :y (double t)}})))
       (let [ip (when (seq indices) (ffi/alloc a (* 4 (count indices))))]
         (when ip (ffi/write-array ip :int (int-array indices)))
         (core/check-bool "SDL_RenderGeometry"
                          (render/render-geometry ren (or tex ffi/null) vp n (or ip ffi/null) (count indices))))))
   nil))

;; ---------------------------------------------------------------------------
;; render state
;; ---------------------------------------------------------------------------

(defn set-vsync!
  "SDL_SetRenderVSync: true (every refresh), false, an integer (every n-th
  refresh), or :adaptive."
  [ren v]
  (core/check-bool "SDL_SetRenderVSync"
                   (render/set-render-vsync ren (int (case v true 1 false 0 :adaptive -1 v))))
  nil)

(defn vsync [ren]
  (with-outs [v :int]
    (core/check-bool "SDL_GetRenderVSync" (render/get-render-vsync ren v))
    (ffi/read v :int)))

(defn set-logical-presentation!
  "SDL_SetRenderLogicalPresentation: draw in a w x h coordinate space scaled to
  the output; `mode` :disabled :stretch :letterbox :overscan or :integer-scale."
  [ren w h mode]
  (core/check-bool "SDL_SetRenderLogicalPresentation"
                   (render/set-render-logical-presentation ren (int w) (int h) (core/enum c/renderer-logical-presentation mode)))
  nil)

(defn logical-presentation [ren]
  (with-outs [w :int h :int m :int]
    (core/check-bool "SDL_GetRenderLogicalPresentation" (render/get-render-logical-presentation ren w h m))
    {:w (ffi/read w :int) :h (ffi/read h :int)
     :mode (core/unenum c/renderer-logical-presentation-names (ffi/read m :int))}))

(defn logical-presentation-rect [ren]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-frect a)]
      (core/check-bool "SDL_GetRenderLogicalPresentationRect" (render/get-render-logical-presentation-rect ren r))
      (rect/read-frect r))))

(defn set-scale!
  "SDL_SetRenderScale."
  [ren sx sy]
  (core/check-bool "SDL_SetRenderScale" (render/set-render-scale ren (double sx) (double sy)))
  nil)

(defn scale [ren]
  (with-outs [x :float y :float]
    (core/check-bool "SDL_GetRenderScale" (render/get-render-scale ren x y))
    [(ffi/read x :float) (ffi/read y :float)]))

(defn set-viewport!
  "SDL_SetRenderViewport to an SDL_Rect map, or nil for the whole target."
  [ren r]
  (core/check-bool "SDL_SetRenderViewport" (render/set-render-viewport ren (rect-arg cell-a r)))
  nil)

(defn viewport [ren]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool "SDL_GetRenderViewport" (render/get-render-viewport ren r))
      (rect/read-rect r))))

(defsdl viewport-set? render/render-viewport-set :pred true)

(defn set-clip-rect!
  "SDL_SetRenderClipRect to an SDL_Rect map, or nil to disable clipping."
  [ren r]
  (core/check-bool "SDL_SetRenderClipRect" (render/set-render-clip-rect ren (rect-arg cell-a r)))
  nil)

(defn clip-rect [ren]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool "SDL_GetRenderClipRect" (render/get-render-clip-rect ren r))
      (rect/read-rect r))))

(defsdl clip-enabled? render/render-clip-enabled :pred true)

(defn set-render-target!
  "SDL_SetRenderTarget: draw into `tex` (created with :target access), or nil for the window."
  [ren tex]
  (core/check-bool "SDL_SetRenderTarget" (render/set-render-target ren (or tex ffi/null)))
  nil)

(defsdl render-target render/get-render-target :nullable true)

(defn safe-area [ren]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool "SDL_GetRenderSafeArea" (render/get-render-safe-area ren r))
      (rect/read-rect r))))

(defn read-pixels
  "SDL_RenderReadPixels: a new SDL_Surface* of `rect` (nil for everything) from
  the current target; destroy it with sdl3.surface/destroy-surface!."
  [ren r]
  (core/check-ptr "SDL_RenderReadPixels" (render/render-read-pixels ren (rect-arg cell-a r))))

(defn coordinates-from-window
  "SDL_RenderCoordinatesFromWindow: window [wx wy] to render coordinates [x y]."
  [ren wx wy]
  (with-outs [x :float y :float]
    (core/check-bool "SDL_RenderCoordinatesFromWindow" (render/render-coordinates-from-window ren (double wx) (double wy) x y))
    [(ffi/read x :float) (ffi/read y :float)]))

(defn coordinates-to-window
  "SDL_RenderCoordinatesToWindow: render [x y] to window coordinates [wx wy]."
  [ren x y]
  (with-outs [wx :float wy :float]
    (core/check-bool "SDL_RenderCoordinatesToWindow" (render/render-coordinates-to-window ren (double x) (double y) wx wy))
    [(ffi/read wx :float) (ffi/read wy :float)]))

;; ---------------------------------------------------------------------------
;; custom blend modes
;; ---------------------------------------------------------------------------

(defn compose-blend-mode
  "SDL_ComposeCustomBlendMode: a blend mode integer, usable wherever a blend mode
  keyword is, from

    {:src-color-factor :src-alpha :dst-color-factor :one-minus-src-alpha :color-operation :add
     :src-alpha-factor :one :dst-alpha-factor :one-minus-src-alpha :alpha-operation :add}

  Factors from sdl3.consts/blend-factor, operations from sdl3.consts/blend-operation.
  Not every renderer supports every combination; setting an unsupported one raises."
  [{:keys [src-color-factor dst-color-factor color-operation src-alpha-factor dst-alpha-factor alpha-operation]}]
  (let [bf #(core/enum c/blend-factor %)
        bo #(core/enum c/blend-operation %)]
    (blendmode/compose-custom-blend-mode
     (bf src-color-factor) (bf dst-color-factor) (bo color-operation)
     (bf src-alpha-factor) (bf dst-alpha-factor) (bo alpha-operation))))

;; ---------------------------------------------------------------------------
;; palettes
;; ---------------------------------------------------------------------------

(defsdl set-texture-palette! render/set-texture-palette
  :doc "Give an indexed-format texture (:index8 ...) the SDL_Palette from sdl3.pixels/create-palette.")
(defsdl texture-palette render/get-texture-palette :nullable true)
