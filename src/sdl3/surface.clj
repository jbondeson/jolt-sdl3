(ns sdl3.surface
  "Software surfaces: SDL_surface.h. A surface is the SDL_Surface* pointer;
  destroy-surface! releases it. surface-info reads its header as a map, and the
  accessors below read one field each."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.rect :as rect]
            [sdl3.raw.surface :as surface]))

(def layout "ffi/layout of SDL_Surface." surface/surface)

(defn create-surface
  "SDL_CreateSurface: a new w x h surface in `format` (sdl3.consts/pixel-format)."
  [w h format]
  (core/check-ptr "SDL_CreateSurface" (surface/create-surface (int w) (int h) (core/enum c/pixel-format format))))

(defn create-surface-from
  "SDL_CreateSurfaceFrom: a surface over existing `pixels` (a pointer that must
  outlive it), `pitch` bytes per row."
  [w h format pixels pitch]
  (core/check-ptr "SDL_CreateSurfaceFrom"
                  (surface/create-surface-from (int w) (int h) (core/enum c/pixel-format format) pixels (int pitch))))

(defsdl destroy-surface! surface/destroy-surface)
(defsdl surface-properties surface/get-surface-properties)
(defsdl load-bmp surface/load-bmp)
(defsdl load-bmp-io surface/load-bmp-io)
(defsdl save-bmp! surface/save-bmp)
(defsdl save-bmp-io! surface/save-bmp-io)
(defsdl duplicate-surface surface/duplicate-surface)
(defsdl lock-surface! surface/lock-surface)
(defsdl unlock-surface! surface/unlock-surface)
(defsdl set-surface-rle! surface/set-surface-rle)
(defsdl surface-has-rle? surface/surface-has-rle :pred true)
(defsdl surface-has-color-key? surface/surface-has-color-key :pred true)
(defsdl premultiply-alpha! surface/premultiply-surface-alpha)

;; ---------------------------------------------------------------------------
;; the struct
;; ---------------------------------------------------------------------------

(def ^:private off-w (ffi/field-offset surface/surface :w))
(def ^:private off-h (ffi/field-offset surface/surface :h))
(def ^:private off-pitch (ffi/field-offset surface/surface :pitch))
(def ^:private off-pixels (ffi/field-offset surface/surface :pixels))
(def ^:private off-format (ffi/field-offset surface/surface :format))
(def ^:private off-flags (ffi/field-offset surface/surface :flags))

(defn width [s] (ffi/read s :int off-w))
(defn height [s] (ffi/read s :int off-h))
(defn pitch "Bytes per row." [s] (ffi/read s :int off-pitch))
(defn pixels "The pixel buffer pointer (lock-surface! first when must-lock?)." [s] (ffi/read s :pointer off-pixels))
(defn format [s] (core/unenum c/pixel-format-names (ffi/read s :int off-format)))
(defn flags [s] (core/unflag c/surface-flags (ffi/read s :uint32 off-flags)))
(defn must-lock? "SDL_MUSTLOCK." [s] (contains? (flags s) :lock-needed))

(defn surface-info
  "The SDL_Surface header as {:flags :format :w :h :pitch :pixels}."
  [s]
  (-> (ffi/read s surface/surface)
      (update :flags #(core/unflag c/surface-flags %))
      (update :format #(core/unenum c/pixel-format-names %))
      (dissoc :refcount :reserved)))

;; ---------------------------------------------------------------------------
;; conversion
;; ---------------------------------------------------------------------------

(defn convert-surface
  "SDL_ConvertSurface: a copy in another pixel format."
  [s format]
  (core/check-ptr "SDL_ConvertSurface" (surface/convert-surface s (core/enum c/pixel-format format))))

(defn scale-surface
  "SDL_ScaleSurface: a copy scaled to w x h with `mode` :nearest or :linear."
  [s w h mode]
  (core/check-ptr "SDL_ScaleSurface" (surface/scale-surface s (int w) (int h) (core/enum c/scale-mode mode))))

(defn flip-surface!
  "SDL_FlipSurface in place: :horizontal, :vertical or both."
  [s mode]
  (core/check-bool "SDL_FlipSurface" (surface/flip-surface s (core/flags c/flip-mode mode)))
  nil)

;; ---------------------------------------------------------------------------
;; color key, modulation, blending, clipping
;; ---------------------------------------------------------------------------

(defn set-color-key!
  "SDL_SetSurfaceColorKey: the pixel value (from map-rgb) treated as transparent
  when blitting, or nil to disable."
  [s key]
  (core/check-bool "SDL_SetSurfaceColorKey" (surface/set-surface-color-key s (boolean key) (int (or key 0))))
  nil)

(defn color-key [s]
  (with-outs [k :uint32]
    (core/check-bool "SDL_GetSurfaceColorKey" (surface/get-surface-color-key s k))
    (ffi/read k :uint32)))

(defn set-color-mod! [s r g b]
  (core/check-bool "SDL_SetSurfaceColorMod" (surface/set-surface-color-mod s (int r) (int g) (int b)))
  nil)

(defn color-mod [s]
  (with-outs [r :uint8 g :uint8 b :uint8]
    (core/check-bool "SDL_GetSurfaceColorMod" (surface/get-surface-color-mod s r g b))
    [(ffi/read r :uint8) (ffi/read g :uint8) (ffi/read b :uint8)]))

(defn set-alpha-mod! [s a]
  (core/check-bool "SDL_SetSurfaceAlphaMod" (surface/set-surface-alpha-mod s (int a)))
  nil)

(defn alpha-mod [s]
  (with-outs [a :uint8]
    (core/check-bool "SDL_GetSurfaceAlphaMod" (surface/get-surface-alpha-mod s a))
    (ffi/read a :uint8)))

(defn set-blend-mode! [s mode]
  (core/check-bool "SDL_SetSurfaceBlendMode" (surface/set-surface-blend-mode s (core/flags c/blend-mode mode)))
  nil)

(defn blend-mode [s]
  (with-outs [m :uint32]
    (core/check-bool "SDL_GetSurfaceBlendMode" (surface/get-surface-blend-mode s m))
    (let [v (ffi/read m :uint32)] (or (some (fn [[k b]] (when (= b v) k)) c/blend-mode) v))))

(defn set-clip-rect!
  "SDL_SetSurfaceClipRect to an SDL_Rect map (nil: the whole surface); answers
  whether the rect intersects the surface."
  [s r]
  (with-open [a (ffi/confined-arena)]
    (surface/set-surface-clip-rect s (if r (rect/alloc-rect a r) ffi/null))))

(defn clip-rect [s]
  (with-open [a (ffi/confined-arena)]
    (let [r (rect/alloc-rect a)]
      (core/check-bool "SDL_GetSurfaceClipRect" (surface/get-surface-clip-rect s r))
      (rect/read-rect r))))

;; ---------------------------------------------------------------------------
;; pixels
;; ---------------------------------------------------------------------------

(defn map-rgb
  "SDL_MapSurfaceRGB: the pixel value for r g b in this surface's format."
  [s r g b]
  (surface/map-surface-rgb s (int r) (int g) (int b)))

(defn map-rgba [s r g b a]
  (surface/map-surface-rgba s (int r) (int g) (int b) (int a)))

(defn read-pixel
  "SDL_ReadSurfacePixel at x, y as {:r :g :b :a} (0-255)."
  [s x y]
  (with-outs [r :uint8 g :uint8 b :uint8 a :uint8]
    (core/check-bool "SDL_ReadSurfacePixel" (surface/read-surface-pixel s (int x) (int y) r g b a))
    {:r (ffi/read r :uint8) :g (ffi/read g :uint8) :b (ffi/read b :uint8) :a (ffi/read a :uint8)}))

(defn write-pixel!
  "SDL_WriteSurfacePixel."
  ([s x y r g b] (write-pixel! s x y r g b 255))
  ([s x y r g b a]
   (core/check-bool "SDL_WriteSurfacePixel" (surface/write-surface-pixel s (int x) (int y) (int r) (int g) (int b) (int a)))
   nil))

(defn clear!
  "SDL_ClearSurface with float components 0.0-1.0."
  [s r g b a]
  (core/check-bool "SDL_ClearSurface" (surface/clear-surface s (double r) (double g) (double b) (double a)))
  nil)

(defn fill-rect!
  "SDL_FillSurfaceRect with pixel value `color` (from map-rgb); `r` nil fills all."
  [s r color]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_FillSurfaceRect" (surface/fill-surface-rect s (if r (rect/alloc-rect a r) ffi/null) (int color))))
  nil)

(defn fill-rects! [s rs color]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (rect/rects a rs)]
      (core/check-bool "SDL_FillSurfaceRects" (surface/fill-surface-rects s p n (int color)))))
  nil)

;; ---------------------------------------------------------------------------
;; blitting
;; ---------------------------------------------------------------------------

(defn- rect-or-null [a r] (if r (rect/alloc-rect a r) ffi/null))

(defn blit!
  "SDL_BlitSurface: copy `src-rect` of `src` (nil: all) to `dst` at `dst-rect`'s
  :x :y (nil: 0,0), unscaled."
  [src src-rect dst dst-rect]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_BlitSurface" (surface/blit-surface src (rect-or-null a src-rect) dst (rect-or-null a dst-rect))))
  nil)

(defn blit-scaled!
  "SDL_BlitSurfaceScaled: as blit!, scaled to fill `dst-rect`, with `mode` :nearest or :linear."
  [src src-rect dst dst-rect mode]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_BlitSurfaceScaled"
                     (surface/blit-surface-scaled src (rect-or-null a src-rect) dst (rect-or-null a dst-rect) (core/enum c/scale-mode mode))))
  nil)

(defn blit-tiled!
  "SDL_BlitSurfaceTiled: tile `src-rect` across `dst-rect`."
  [src src-rect dst dst-rect]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_BlitSurfaceTiled" (surface/blit-surface-tiled src (rect-or-null a src-rect) dst (rect-or-null a dst-rect))))
  nil)

(defn blit-9-grid!
  "SDL_BlitSurface9Grid: a 9-slice of `src-rect` with the given border widths into `dst-rect`."
  [src src-rect left right top bottom scale mode dst dst-rect]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_BlitSurface9Grid"
                     (surface/blit-surface-9-grid src (rect-or-null a src-rect) (int left) (int right) (int top) (int bottom)
                                                  (double scale) (core/enum c/scale-mode mode) dst (rect-or-null a dst-rect))))
  nil)

(defn stretch!
  "SDL_StretchSurface: scale `src-rect` into `dst-rect` without clipping or blending."
  [src src-rect dst dst-rect mode]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_StretchSurface"
                     (surface/stretch-surface src (rect-or-null a src-rect) dst (rect-or-null a dst-rect) (core/enum c/scale-mode mode))))
  nil)
