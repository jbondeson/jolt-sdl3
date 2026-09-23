(ns sdl3.image
  "Image loading and saving beyond BMP and PNG: SDL_image (SDL3_image.h). Needs
  libSDL3_image installed (`brew install sdl3_image`); it is an optional native,
  so check available? before relying on it.

  Loads JPEG, WebP, AVIF, JPEG XL, GIF, TIFF, QOI, SVG, TGA, ICO/CUR, PNM, XCF,
  LBM, PCX, XPM and XV, plus BMP and PNG, into sdl3.surface surfaces or straight
  into textures; saves PNG, JPEG, WebP, AVIF, GIF, TGA, BMP, ICO and CUR; and
  reads and writes animations (GIF, APNG, WebP, AVIF, ANI).

      (def tex (load-texture renderer \"hero.webp\"))
      (let [s (load \"photo.jpg\")] (save! s \"photo.png\") (sdl3.surface/destroy-surface! s))

  Formats are keywords: :avif :bmp :cur :gif :ico :jpg :jxl :lbm :pcx :png
  :pnm :qoi :svg :tga :tif :webp :xcf :xpm :xv, and :ani and :apng for
  animations."
  (:refer-clojure :exclude [load])
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts.image :as c]
            [sdl3.surface :as surface]
            [sdl3.raw.image :as img]))

(def ^:private availability
  (delay (try (img/version) true (catch Throwable _ false))))

(defn available?
  "Is libSDL3_image installed and loaded? Every other function throws when it isn't."
  []
  @availability)

(defn version
  "The linked SDL_image's version: {:major :minor :micro :string}."
  []
  (let [v (img/version)
        major (quot v 1000000) minor (mod (quot v 1000) 1000) micro (mod v 1000)]
    {:major major :minor minor :micro micro :string (str major "." minor "." micro)}))

(defn- type-name
  "A format keyword as the type string SDL_image's *Typed_IO functions take."
  [fmt]
  (if (keyword? fmt) (str/upper-case (name fmt)) (str fmt)))

;; ---------------------------------------------------------------------------
;; loading
;; ---------------------------------------------------------------------------

(defsdl load img/load
  :doc "IMG_Load: a new surface from an image file in any supported format.")

(defn load-io
  "IMG_Load_IO / IMG_LoadTyped_IO: a surface from an sdl3.io stream, closing it
  when `close?`. `fmt` hints the format for streams that cannot be sniffed (TGA)."
  ([stream close?] (core/check-ptr "IMG_Load_IO" (img/load-io stream (boolean close?))))
  ([stream close? fmt] (core/check-ptr "IMG_LoadTyped_IO" (img/load-typed-io stream (boolean close?) (type-name fmt)))))

(defsdl load-texture img/load-texture
  :doc "IMG_LoadTexture: a texture on `renderer` from an image file.")

(defn load-texture-io
  "IMG_LoadTexture_IO / IMG_LoadTextureTyped_IO, as load-io."
  ([renderer stream close?] (core/check-ptr "IMG_LoadTexture_IO" (img/load-texture-io renderer stream (boolean close?))))
  ([renderer stream close? fmt]
   (core/check-ptr "IMG_LoadTextureTyped_IO" (img/load-texture-typed-io renderer stream (boolean close?) (type-name fmt)))))

(defn load-gpu-texture
  "IMG_LoadGPUTexture: an sdl3.gpu texture uploaded through `copy-pass`, as
  {:texture :width :height}. Submit the pass's command buffer before using it."
  [device copy-pass path]
  (with-outs [w :int h :int]
    (let [t (core/check-ptr "IMG_LoadGPUTexture" (img/load-gpu-texture device copy-pass (str path) w h))]
      {:texture t :width (ffi/read w :int) :height (ffi/read h :int)})))

(defn load-sized-svg-io
  "IMG_LoadSizedSVG_IO: rasterize an SVG stream at `w` x `h` (0 keeps that side's aspect)."
  [stream w h]
  (core/check-ptr "IMG_LoadSizedSVG_IO" (img/load-sized-svg-io stream (int w) (int h))))

(defn read-xpm
  "IMG_ReadXPMFromArray: a surface from an XPM image given as its lines (strings)."
  [lines]
  (with-open [a (ffi/confined-arena)]
    (let [[p _] (core/alloc-pointers a (map #(ffi/string->ptr a %) lines))]
      (core/check-ptr "IMG_ReadXPMFromArray" (img/read-xpm-from-array p)))))

(defn clipboard-image
  "IMG_GetClipboardImage: the clipboard's image as a surface, or nil when it holds none."
  []
  (core/nullable (img/get-clipboard-image)))

;; ---------------------------------------------------------------------------
;; format detection
;; ---------------------------------------------------------------------------

(def ^:private detectors
  [[:ani img/is-ani] [:avif img/is-avif] [:bmp img/is-bmp] [:cur img/is-cur] [:gif img/is-gif]
   [:ico img/is-ico] [:jpg img/is-jpg] [:jxl img/is-jxl] [:lbm img/is-lbm] [:pcx img/is-pcx]
   [:png img/is-png] [:pnm img/is-pnm] [:qoi img/is-qoi] [:svg img/is-svg] [:tif img/is-tif]
   [:webp img/is-webp] [:xcf img/is-xcf] [:xpm img/is-xpm] [:xv img/is-xv]])

(defn format-of
  "The format of the image in `stream` (read from its current position, which is
  left unchanged), as a keyword, or nil when SDL_image does not recognize it. TGA
  has no signature and is never detected."
  [stream]
  (some (fn [[k f]] (when (f stream) k)) detectors))

(defn format?
  "Is the image in `stream` of format `fmt`?"
  [stream fmt]
  (let [f (or (some (fn [[k f]] (when (= k fmt) f)) detectors)
              (throw (ex-info (str "no detector for " fmt) {:format fmt :known (map first detectors)})))]
    (f stream)))

;; ---------------------------------------------------------------------------
;; saving
;; ---------------------------------------------------------------------------

(defsdl save! img/save
  :doc "IMG_Save: write a surface to a file in the format its extension names.")

(defn save-as!
  "Write surface `s` to `path` in format `fmt`, whatever the extension. `quality`
  applies to :jpg (0-100, default 90), :webp (0-100, default 90) and :avif (0-100,
  default 90)."
  ([s path fmt] (save-as! s path fmt nil))
  ([s path fmt quality]
   (let [q (or quality 90)
         path (str path)
         ok (case fmt
              :png (img/save-png s path)
              :jpg (img/save-jpg s path (int q))
              :webp (img/save-webp s path (double q))
              :avif (img/save-avif s path (int q))
              :gif (img/save-gif s path)
              :bmp (img/save-bmp s path)
              ;; SDL_image writes TGA from only a few pixel formats, so convert first
              :tga (let [conv (surface/convert-surface s :rgba32)]
                     (try (img/save-tga conv path) (finally (surface/destroy-surface! conv))))
              :ico (img/save-ico s path)
              :cur (img/save-cur s path)
              (throw (ex-info (str "SDL_image cannot save " fmt) {:format fmt})))]
     (core/check-bool (str "IMG_Save " (name fmt)) ok)
     nil)))

(defn save-io!
  "IMG_SaveTyped_IO: write surface `s` to an sdl3.io stream as `fmt`, closing the
  stream when `close?`."
  [s stream close? fmt]
  (core/check-bool "IMG_SaveTyped_IO" (img/save-typed-io s stream (boolean close?) (type-name fmt)))
  nil)

;; ---------------------------------------------------------------------------
;; animations
;; ---------------------------------------------------------------------------

(defsdl load-animation img/load-animation
  :doc "IMG_LoadAnimation: every frame of an animated GIF, APNG, WebP, AVIF or ANI
  file at once. Read it with animation-info; free it with free-animation!.")

(defn load-animation-io
  ([stream close?] (core/check-ptr "IMG_LoadAnimation_IO" (img/load-animation-io stream (boolean close?))))
  ([stream close? fmt] (core/check-ptr "IMG_LoadAnimationTyped_IO" (img/load-animation-typed-io stream (boolean close?) (type-name fmt)))))

(defn animation-info
  "An IMG_Animation as {:w :h :count :frames [surface ...] :delays [ms ...]}. The
  frames belong to the animation and go away with free-animation!."
  [anim]
  (let [{:keys [w h count frames delays]} (ffi/read anim img/animation)
        w-ptr (ffi/sizeof :pointer)]
    {:w w :h h :count count
     :frames (mapv #(ffi/read frames :pointer (* % w-ptr)) (range count))
     :delays (mapv #(ffi/read delays :int (* % 4)) (range count))}))

(defsdl free-animation! img/free-animation)

(defn save-animation!
  "IMG_SaveAnimation: write an animation to a file in the format its extension names."
  [anim path]
  (core/check-bool "IMG_SaveAnimation" (img/save-animation anim (str path)))
  nil)

(defn create-animated-cursor
  "IMG_CreateAnimatedCursor: a cursor that plays `anim`, set with sdl3.mouse/set-cursor!."
  [anim hot-x hot-y]
  (core/check-ptr "IMG_CreateAnimatedCursor" (img/create-animated-cursor anim (int hot-x) (int hot-y))))

;; ---------------------------------------------------------------------------
;; streaming encoders and decoders
;; ---------------------------------------------------------------------------

(defsdl create-encoder img/create-animation-encoder
  :doc "IMG_CreateAnimationEncoder: write an animation to `file` frame by frame
  (format from the extension). Add frames with add-frame!, finish with close-encoder!.")

(defn add-frame!
  "IMG_AddAnimationEncoderFrame: append surface `s`, shown for `duration-ms`."
  [encoder s duration-ms]
  (core/check-bool "IMG_AddAnimationEncoderFrame" (img/add-animation-encoder-frame encoder s (long duration-ms)))
  nil)

(defsdl close-encoder! img/close-animation-encoder
  :doc "Finish the file; the encoder is gone afterwards.")

(defsdl create-decoder img/create-animation-decoder
  :doc "IMG_CreateAnimationDecoder: read an animation frame by frame with
  next-frame, without holding every frame in memory.")

(defn next-frame
  "IMG_GetAnimationDecoderFrame: {:surface :duration-ms} for the next frame, or nil
  when the animation is over (see decoder-status). The surface is yours to destroy."
  [decoder]
  (with-outs [pp :pointer dur :uint64]
    (when (img/get-animation-decoder-frame decoder pp dur)
      {:surface (ffi/read pp :pointer) :duration-ms (ffi/read dur :uint64)})))

(defn decoder-status
  "IMG_GetAnimationDecoderStatus: :ok, :failed or :complete."
  [decoder]
  (core/unenum c/animation-decoder-status-names (img/get-animation-decoder-status decoder)))

(defsdl decoder-properties img/get-animation-decoder-properties)
(defsdl reset-decoder! img/reset-animation-decoder)
(defsdl close-decoder! img/close-animation-decoder)
