(ns sdl3.pixels
  "Pixel formats and palettes: SDL_pixels.h. Formats are keywords from
  sdl3.consts/pixel-format (:rgba8888, :abgr8888, :index8 ...)."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.pixels :as px]))

(defn- fmt [f] (core/enum c/pixel-format f))

(defn format-name "SDL_GetPixelFormatName, e.g. \"SDL_PIXELFORMAT_RGBA8888\"." [f] (px/get-pixel-format-name (fmt f)))

(defn masks
  "SDL_GetMasksForPixelFormat: {:bpp :rmask :gmask :bmask :amask}."
  [f]
  (with-outs [bpp :int r :uint32 g :uint32 b :uint32 a :uint32]
    (core/check-bool "SDL_GetMasksForPixelFormat" (px/get-masks-for-pixel-format (fmt f) bpp r g b a))
    {:bpp (ffi/read bpp :int) :rmask (ffi/read r :uint32) :gmask (ffi/read g :uint32)
     :bmask (ffi/read b :uint32) :amask (ffi/read a :uint32)}))

(defn format-for-masks
  "SDL_GetPixelFormatForMasks: the format keyword for {:bpp :rmask :gmask :bmask :amask}."
  [{:keys [bpp rmask gmask bmask amask]}]
  (core/unenum c/pixel-format-names (px/get-pixel-format-for-masks (int bpp) rmask gmask bmask (or amask 0))))

(defn details-ptr
  "SDL_GetPixelFormatDetails: the SDL-owned details pointer map-rgb and friends take."
  [f]
  (core/check-ptr "SDL_GetPixelFormatDetails" (px/get-pixel-format-details (fmt f))))

(defn details
  "SDL_GetPixelFormatDetails as a map: :bits-per-pixel :bytes-per-pixel,
  :rmask..:amask, :rbits..:abits, :rshift..:ashift."
  [f]
  (-> (ffi/read (details-ptr f) px/pixel-format-details)
      (update :format #(core/unenum c/pixel-format-names %))
      (dissoc :padding)))

(defn map-rgba
  "SDL_MapRGBA: the pixel value for r g b a in format `f` (with `palette` for
  indexed formats)."
  ([f r g b a] (map-rgba f nil r g b a))
  ([f palette r g b a] (px/map-rgba (details-ptr f) (or palette ffi/null) (int r) (int g) (int b) (int a))))

(defn map-rgb
  ([f r g b] (map-rgb f nil r g b))
  ([f palette r g b] (px/map-rgb (details-ptr f) (or palette ffi/null) (int r) (int g) (int b))))

(defn rgba
  "SDL_GetRGBA: pixel value `v` in format `f` as {:r :g :b :a}."
  ([v f] (rgba v f nil))
  ([v f palette]
   (with-outs [r :uint8 g :uint8 b :uint8 a :uint8]
     (px/get-rgba v (details-ptr f) (or palette ffi/null) r g b a)
     {:r (ffi/read r :uint8) :g (ffi/read g :uint8) :b (ffi/read b :uint8) :a (ffi/read a :uint8)})))

;; ---------------------------------------------------------------------------
;; palettes
;; ---------------------------------------------------------------------------

(declare set-colors!)

(defn create-palette
  "SDL_CreatePalette with `n` colors (initially white), optionally set from `colors`."
  ([n] (core/check-ptr "SDL_CreatePalette" (px/create-palette (int n))))
  ([n colors] (let [p (create-palette n)] (set-colors! p colors 0) p)))

(defsdl destroy-palette! px/destroy-palette)

(defn- color-map [c]
  (if (map? c) (merge {:a 255} c) (let [[r g b a] c] {:r r :g g :b b :a (or a 255)})))

(defn set-colors!
  "SDL_SetPaletteColors from `first` (default 0): colors are {:r :g :b :a} or [r g b a]."
  ([palette colors] (set-colors! palette colors 0))
  ([palette colors first]
   (with-open [a (ffi/confined-arena)]
     (let [[p n] (core/alloc-array a px/color (map color-map colors))]
       (core/check-bool "SDL_SetPaletteColors" (px/set-palette-colors palette p (int first) (int n)))))
   nil))

(defn colors
  "The palette's colors as a vector of {:r :g :b :a}."
  [palette]
  (let [{:keys [ncolors] cs :colors} (ffi/read palette px/palette)
        size (ffi/layout-size px/color)]
    (mapv (fn [i] (ffi/read (ffi/slice cs (* i size)) px/color)) (range ncolors))))
