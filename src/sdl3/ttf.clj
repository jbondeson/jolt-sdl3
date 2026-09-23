(ns sdl3.ttf
  "TrueType and OpenType text: SDL_ttf (SDL3_ttf). Needs libSDL3_ttf installed
  (`brew install sdl3_ttf`); it is an optional native, so check available?
  before relying on it.

  Two ways to draw text:

  - **Render to a surface** — render-text makes an SDL_Surface of a string, to
    upload as a texture or blit. Simple, and fine for text that rarely changes.
  - **Text objects** — create a text engine for a renderer, a GPU device or a
    surface, then create-text; the engine caches glyphs in an atlas, so editing
    and redrawing text every frame is cheap.

      (ttf/init!)
      (let [font (ttf/open-font \"DejaVuSans.ttf\" 24)
            engine (ttf/create-renderer-text-engine renderer)
            text (ttf/create-text engine font \"Hello, SDL_ttf\")]
        ;; each frame:
        (ttf/draw-renderer-text! text 20 20))

  Colors are [r g b a] or {:r :g :b :a}, 0-255 (alpha defaults to 255). Styles
  are sets of :bold :italic :underline :strikethrough."
  (:refer-clojure :exclude [text])
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts.ttf :as c]
            [sdl3.raw.pixels :as pixels]
            [sdl3.raw.ttf :as ttf]))

(def ^:private availability
  (delay (try (ttf/version) true (catch Throwable _ false))))

(defn available?
  "Is libSDL3_ttf installed and loaded? Every other function throws when it isn't."
  []
  @availability)

;; ---------------------------------------------------------------------------
;; library
;; ---------------------------------------------------------------------------

(defn init!
  "TTF_Init. Pair with quit!; calls nest, so init! and quit! may be repeated."
  []
  (core/check-bool "TTF_Init" (ttf/init))
  nil)

(defsdl quit! ttf/quit)
(defn initialized? "TTF_WasInit." [] (pos? (ttf/was-init)))

(defmacro with-ttf
  "Run body between init! and quit!."
  [& body]
  `(do (init!) (try ~@body (finally (quit!)))))

(defn- version-map [v]
  (let [major (quot v 1000000) minor (mod (quot v 1000) 1000) micro (mod v 1000)]
    {:major major :minor minor :micro micro :string (str major "." minor "." micro)}))

(defn version "The linked SDL_ttf's version." [] (version-map (ttf/version)))

(defn- triple [f]
  (with-outs [a :int b :int d :int]
    (f a b d)
    (let [[x y z] [(ffi/read a :int) (ffi/read b :int) (ffi/read d :int)]]
      {:major x :minor y :micro z :string (str x "." y "." z)})))

(defn freetype-version
  "The FreeType SDL_ttf uses. FreeType reports it only once init! has run, so this
  answers 0.0.0 before then."
  []
  (triple ttf/get-freetype-version))
(defn harfbuzz-version "The HarfBuzz SDL_ttf was built with, or 0.0.0 without it." [] (triple ttf/get-harfbuzz-version))

;; ---------------------------------------------------------------------------
;; colors
;; ---------------------------------------------------------------------------

(defn- rgba [color]
  (if (map? color)
    [(:r color) (:g color) (:b color) (:a color 255)]
    (let [[r g b a] color] [r g b (or a 255)])))

(defn- color-ptr
  "An SDL_Color in `arena`, for the by-value color arguments."
  [arena color]
  (let [[r g b a] (rgba color)
        p (ffi/alloc arena pixels/color)]
    (ffi/write p pixels/color {:r (int r) :g (int g) :b (int b) :a (int a)})
    p))

;; ---------------------------------------------------------------------------
;; fonts
;; ---------------------------------------------------------------------------

(defn open-font
  "TTF_OpenFont: `path` (TTF, OTF, TTC, WOFF ...) at `size` points."
  [path size]
  (core/check-ptr "TTF_OpenFont" (ttf/open-font (str path) (double size))))

(defn open-font-io
  "TTF_OpenFontIO from an sdl3.io stream, closing it when `close?`."
  [stream close? size]
  (core/check-ptr "TTF_OpenFontIO" (ttf/open-font-io stream (boolean close?) (double size))))

(defsdl open-font-with-properties ttf/open-font-with-properties)
(defsdl copy-font ttf/copy-font)
(defsdl close-font! ttf/close-font)

(defmacro with-font
  "Bind a font opened from `path` at `size` for the body, closing it after."
  [[sym path size] & body]
  `(let [~sym (open-font ~path ~size)]
     (try ~@body (finally (ttf/close-font ~sym)))))

(defsdl font-properties ttf/get-font-properties)
(defsdl font-generation ttf/get-font-generation)
(defsdl add-fallback-font! ttf/add-fallback-font
  :doc "Use `fallback` for glyphs `font` lacks (emoji, other scripts).")
(defsdl remove-fallback-font! ttf/remove-fallback-font)
(defsdl clear-fallback-fonts! ttf/clear-fallback-fonts)

(defsdl set-size! ttf/set-font-size)
(defn set-size-dpi! [font size hdpi vdpi]
  (core/check-bool "TTF_SetFontSizeDPI" (ttf/set-font-size-dpi font (double size) (int hdpi) (int vdpi)))
  nil)
(defsdl size ttf/get-font-size)
(defn dpi [font]
  (with-outs [h :int v :int]
    (core/check-bool "TTF_GetFontDPI" (ttf/get-font-dpi font h v))
    [(ffi/read h :int) (ffi/read v :int)]))

(defn style
  "TTF_GetFontStyle as a set of :bold :italic :underline :strikethrough (empty for normal)."
  [font]
  (core/unflag (dissoc c/style :normal) (ttf/get-font-style font)))

(defn set-style!
  "TTF_SetFontStyle: a keyword, a collection of them, or nil / :normal for plain."
  [font styles]
  (ttf/set-font-style font (core/flags c/style styles))
  nil)

(defsdl outline ttf/get-font-outline)
(defsdl set-outline! ttf/set-font-outline
  :doc "Outline width in pixels; 0 for none.")

(defn hinting [font] (core/unenum c/hinting-flags-names (ttf/get-font-hinting font)))
(defn set-hinting!
  "TTF_SetFontHinting: :normal :light :mono :none or :light-subpixel."
  [font h]
  (ttf/set-font-hinting font (core/enum c/hinting-flags h))
  nil)

(defsdl sdf? ttf/get-font-sdf :pred true)
(defsdl set-sdf! ttf/set-font-sdf
  :doc "Render signed-distance-field glyphs, for scaling in a shader.")
(defsdl weight ttf/get-font-weight
  :doc "The font's weight: 400 normal, 700 bold ... (sdl3.consts.ttf/font-weight).")
(defsdl num-faces ttf/get-num-font-faces)

(defn wrap-alignment [font] (core/unenum c/horizontal-alignment-names (ttf/get-font-wrap-alignment font)))
(defn set-wrap-alignment!
  "How wrapped lines align: :left :center or :right."
  [font a]
  (ttf/set-font-wrap-alignment font (core/enum c/horizontal-alignment a))
  nil)

(defsdl height ttf/get-font-height)
(defsdl ascent ttf/get-font-ascent)
(defsdl descent ttf/get-font-descent)
(defsdl line-skip ttf/get-font-line-skip
  :doc "The recommended distance between baselines.")
(defsdl set-line-skip! ttf/set-font-line-skip)
(defsdl kerning? ttf/get-font-kerning :pred true)
(defsdl set-kerning! ttf/set-font-kerning)
(defsdl fixed-width? ttf/font-is-fixed-width :pred true)
(defsdl scalable? ttf/font-is-scalable :pred true)
(defsdl family-name ttf/get-font-family-name :nullable true)
(defsdl style-name ttf/get-font-style-name :nullable true)

(defn direction [font] (core/unenum c/direction-names (ttf/get-font-direction font)))
(defn set-direction!
  "TTF_SetFontDirection: :ltr :rtl :ttb or :btt (needs HarfBuzz)."
  [font d]
  (core/check-bool "TTF_SetFontDirection" (ttf/set-font-direction font (core/enum c/direction d)))
  nil)

(defn- tag->string [tag]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a 5)]
      (ttf/tag-to-string tag p 5)
      (ffi/ptr->string p))))

(defn script
  "TTF_GetFontScript as an ISO 15924 code, e.g. \"Latn\"; \"\" when unset."
  [font]
  (let [t (ttf/get-font-script font)] (if (zero? t) "" (tag->string t))))

(defn set-script!
  "TTF_SetFontScript from an ISO 15924 code, e.g. \"Arab\" (needs HarfBuzz)."
  [font code]
  (core/check-bool "TTF_SetFontScript" (ttf/set-font-script font (ttf/string-to-tag code)))
  nil)

(defn glyph-script
  "TTF_GetGlyphScript: the ISO 15924 script of a character or code point."
  [ch]
  (tag->string (ttf/get-glyph-script (int ch))))

(defsdl set-language! ttf/set-font-language
  :doc "The BCP 47 language for shaping, e.g. \"tr\" (needs HarfBuzz).")

;; ---------------------------------------------------------------------------
;; glyphs and measuring
;; ---------------------------------------------------------------------------

(defn has-glyph?
  "Does the font have a glyph for `ch` (a character or code point)?"
  [font ch]
  (ttf/font-has-glyph font (int ch)))

(defn glyph-metrics
  "TTF_GetGlyphMetrics: {:minx :maxx :miny :maxy :advance} in pixels."
  [font ch]
  (with-outs [minx :int maxx :int miny :int maxy :int adv :int]
    (core/check-bool "TTF_GetGlyphMetrics" (ttf/get-glyph-metrics font (int ch) minx maxx miny maxy adv))
    {:minx (ffi/read minx :int) :maxx (ffi/read maxx :int) :miny (ffi/read miny :int)
     :maxy (ffi/read maxy :int) :advance (ffi/read adv :int)}))

(defn glyph-kerning
  "TTF_GetGlyphKerning: the kerning adjustment between two characters, in pixels."
  [font prev ch]
  (with-outs [k :int]
    (core/check-bool "TTF_GetGlyphKerning" (ttf/get-glyph-kerning font (int prev) (int ch) k))
    (ffi/read k :int)))

(defn glyph-image
  "TTF_GetGlyphImage: {:surface :image-type} for one glyph, image-type :alpha,
  :color or :sdf. The surface is yours to destroy."
  [font ch]
  (with-outs [t :int]
    (let [s (core/check-ptr "TTF_GetGlyphImage" (ttf/get-glyph-image font (int ch) t))]
      {:surface s :image-type (core/unenum c/image-type-names (ffi/read t :int))})))

(defn string-size
  "TTF_GetStringSize: [w h] of `s` on one line; with `wrap-width`, wrapped."
  ([font s]
   (with-outs [w :int h :int]
     (core/check-bool "TTF_GetStringSize" (ttf/get-string-size font (str s) 0 w h))
     [(ffi/read w :int) (ffi/read h :int)]))
  ([font s wrap-width]
   (with-outs [w :int h :int]
     (core/check-bool "TTF_GetStringSizeWrapped" (ttf/get-string-size-wrapped font (str s) 0 (int wrap-width) w h))
     [(ffi/read w :int) (ffi/read h :int)])))

(defn measure-string
  "TTF_MeasureString: how much of `s` fits in `max-width` pixels, as {:width
  pixels :text the-prefix-that-fits}."
  [font s max-width]
  (with-outs [w :int n :size_t]
    (core/check-bool "TTF_MeasureString" (ttf/measure-string font (str s) 0 (int max-width) w n))
    (let [bytes (.getBytes ^String (str s) "UTF-8")
          fit (min (alength bytes) (ffi/read n :size_t))]
      {:width (ffi/read w :int)
       :text (String. bytes 0 (int fit) "UTF-8")})))

;; ---------------------------------------------------------------------------
;; rendering to surfaces
;; ---------------------------------------------------------------------------

(defn render-text
  "Render `s` to a new SDL_Surface (yours to destroy). Options:

    :mode        :blended (default; antialiased, transparent background),
                 :solid (fast, no antialiasing), :shaded (antialiased onto
                 :background) or :lcd (subpixel onto :background)
    :color       the text color (default white)
    :background  for :shaded and :lcd (default black)
    :wrap        wrap at this many pixels; 0 wraps only at newlines"
  ([font s] (render-text font s {}))
  ([font s {:keys [mode color background wrap] :or {mode :blended color [255 255 255 255] background [0 0 0 255]}}]
   (with-open [a (ffi/confined-arena)]
     (let [s (str s)
           fg (color-ptr a color)
           bg (color-ptr a background)
           w (when wrap (int wrap))
           p (case mode
               :blended (if w (ttf/render-text-blended-wrapped font s 0 fg w) (ttf/render-text-blended font s 0 fg))
               :solid (if w (ttf/render-text-solid-wrapped font s 0 fg w) (ttf/render-text-solid font s 0 fg))
               :shaded (if w (ttf/render-text-shaded-wrapped font s 0 fg bg w) (ttf/render-text-shaded font s 0 fg bg))
               :lcd (if w (ttf/render-text-lcd-wrapped font s 0 fg bg w) (ttf/render-text-lcd font s 0 fg bg))
               (throw (ex-info (str "unknown render mode " mode) {:mode mode})))]
       (core/check-ptr (str "TTF_RenderText " (name mode)) p)))))

(defn render-glyph
  "Render one glyph (a character or code point) to a new surface; options as
  render-text, without :wrap."
  ([font ch] (render-glyph font ch {}))
  ([font ch {:keys [mode color background] :or {mode :blended color [255 255 255 255] background [0 0 0 255]}}]
   (with-open [a (ffi/confined-arena)]
     (let [fg (color-ptr a color)
           bg (color-ptr a background)
           ch (int ch)
           p (case mode
               :blended (ttf/render-glyph-blended font ch fg)
               :solid (ttf/render-glyph-solid font ch fg)
               :shaded (ttf/render-glyph-shaded font ch fg bg)
               :lcd (ttf/render-glyph-lcd font ch fg bg)
               (throw (ex-info (str "unknown render mode " mode) {:mode mode})))]
       (core/check-ptr (str "TTF_RenderGlyph " (name mode)) p)))))

;; ---------------------------------------------------------------------------
;; text engines
;; ---------------------------------------------------------------------------

;; engine -> :renderer | :gpu | :surface, so destroy-text-engine! calls the right destroy
(def ^:private engine-kinds (atom {}))

(defn- remember [kind engine]
  (swap! engine-kinds assoc engine kind)
  engine)

(defn create-renderer-text-engine
  "TTF_CreateRendererTextEngine: draw text objects through an sdl3.render renderer."
  [renderer]
  (remember :renderer (core/check-ptr "TTF_CreateRendererTextEngine" (ttf/create-renderer-text-engine renderer))))

(defn create-gpu-text-engine
  "TTF_CreateGPUTextEngine: text objects become atlas draw data for an sdl3.gpu
  pipeline; see gpu-draw-data."
  [device]
  (remember :gpu (core/check-ptr "TTF_CreateGPUTextEngine" (ttf/create-gpu-text-engine device))))

(defn create-surface-text-engine
  "TTF_CreateSurfaceTextEngine: draw text objects onto software surfaces."
  []
  (remember :surface (core/check-ptr "TTF_CreateSurfaceTextEngine" (ttf/create-surface-text-engine))))

(defn destroy-text-engine!
  "Destroy an engine from any create-*-text-engine. Destroy its text objects first."
  [engine]
  (case (get @engine-kinds engine)
    :renderer (ttf/destroy-renderer-text-engine engine)
    :gpu (ttf/destroy-gpu-text-engine engine)
    :surface (ttf/destroy-surface-text-engine engine)
    (throw (ex-info "not a text engine created by sdl3.ttf" {:engine engine})))
  (swap! engine-kinds dissoc engine)
  nil)

(defn gpu-winding [engine] (core/unenum c/gpu-text-engine-winding-names (ttf/get-gpu-text-engine-winding engine)))
(defn set-gpu-winding!
  "The triangle winding the GPU engine produces: :clockwise or :counter-clockwise."
  [engine w]
  (ttf/set-gpu-text-engine-winding engine (core/enum c/gpu-text-engine-winding w))
  nil)

;; ---------------------------------------------------------------------------
;; text objects
;; ---------------------------------------------------------------------------

(defn create-text
  "TTF_CreateText: a text object for `engine` (nil for none: measure only) in
  `font` holding `s`. Destroy it with destroy-text!."
  [engine font s]
  (core/check-ptr "TTF_CreateText" (ttf/create-text (or engine ffi/null) font (str s) 0)))

(defsdl destroy-text! ttf/destroy-text)
(defsdl text-properties ttf/get-text-properties)

(defn text
  "The string a text object holds."
  [t]
  (let [p (:text (ffi/read t ttf/text))] (if (ffi/null? p) "" (ffi/ptr->string p))))

(defn num-lines
  "How many lines the text object's string lays out to. SDL fills this in during
  layout, so it lays the text out first."
  [t]
  (core/check-bool "TTF_UpdateText" (ttf/update-text t))
  (:num-lines (ffi/read t ttf/text)))

(defn set-text!
  "TTF_SetTextString: replace the whole string."
  [t s]
  (core/check-bool "TTF_SetTextString" (ttf/set-text-string t (str s) 0))
  nil)

(defn append-text!
  "TTF_AppendTextString."
  [t s]
  (core/check-bool "TTF_AppendTextString" (ttf/append-text-string t (str s) 0))
  nil)

(defn insert-text!
  "TTF_InsertTextString at UTF-8 byte `offset` (-1 appends)."
  [t offset s]
  (core/check-bool "TTF_InsertTextString" (ttf/insert-text-string t (int offset) (str s) 0))
  nil)

(defn delete-text!
  "TTF_DeleteTextString: remove `length` UTF-8 bytes at `offset` (-1: to the end)."
  [t offset length]
  (core/check-bool "TTF_DeleteTextString" (ttf/delete-text-string t (int offset) (int length)))
  nil)

(defsdl set-text-engine! ttf/set-text-engine)
(defsdl text-engine ttf/get-text-engine :nullable true)
(defsdl set-text-font! ttf/set-text-font)
(defsdl text-font ttf/get-text-font :nullable true)
(defsdl update-text! ttf/update-text
  :doc "Lay the text out now instead of at the next draw or measure.")

(defn text-direction [t] (core/unenum c/direction-names (ttf/get-text-direction t)))
(defn set-text-direction! [t d]
  (core/check-bool "TTF_SetTextDirection" (ttf/set-text-direction t (core/enum c/direction d)))
  nil)

(defn text-script [t] (let [v (ttf/get-text-script t)] (if (zero? v) "" (tag->string v))))
(defn set-text-script! [t code]
  (core/check-bool "TTF_SetTextScript" (ttf/set-text-script t (ttf/string-to-tag code)))
  nil)

(defn set-text-color!
  "TTF_SetTextColor from [r g b a] or {:r :g :b :a}, 0-255."
  [t color]
  (let [[r g b a] (rgba color)]
    (core/check-bool "TTF_SetTextColor" (ttf/set-text-color t (int r) (int g) (int b) (int a))))
  nil)

(defn set-text-color-float!
  "TTF_SetTextColorFloat, components 0.0-1.0."
  [t r g b a]
  (core/check-bool "TTF_SetTextColorFloat" (ttf/set-text-color-float t (double r) (double g) (double b) (double a)))
  nil)

(defn text-color [t]
  (with-outs [r :uint8 g :uint8 b :uint8 a :uint8]
    (core/check-bool "TTF_GetTextColor" (ttf/get-text-color t r g b a))
    {:r (ffi/read r :uint8) :g (ffi/read g :uint8) :b (ffi/read b :uint8) :a (ffi/read a :uint8)}))

(defn set-text-position!
  "TTF_SetTextPosition: the offset draw functions add, for GPU and surface text."
  [t x y]
  (core/check-bool "TTF_SetTextPosition" (ttf/set-text-position t (int x) (int y)))
  nil)

(defn text-position [t]
  (with-outs [x :int y :int]
    (core/check-bool "TTF_GetTextPosition" (ttf/get-text-position t x y))
    [(ffi/read x :int) (ffi/read y :int)]))

(defn set-wrap-width!
  "TTF_SetTextWrapWidth in pixels; 0 wraps only at newlines."
  [t w]
  (core/check-bool "TTF_SetTextWrapWidth" (ttf/set-text-wrap-width t (int w)))
  nil)

(defn wrap-width [t]
  (with-outs [w :int]
    (core/check-bool "TTF_GetTextWrapWidth" (ttf/get-text-wrap-width t w))
    (ffi/read w :int)))

(defsdl set-wrap-whitespace-visible! ttf/set-text-wrap-whitespace-visible)
(defsdl wrap-whitespace-visible? ttf/text-wrap-whitespace-visible :pred true)

(defn text-size
  "TTF_GetTextSize: [w h] of the laid-out text."
  [t]
  (with-outs [w :int h :int]
    (core/check-bool "TTF_GetTextSize" (ttf/get-text-size t w h))
    [(ffi/read w :int) (ffi/read h :int)]))

(defn draw-renderer-text!
  "TTF_DrawRendererText: draw a text object from a renderer engine at x, y."
  [t x y]
  (core/check-bool "TTF_DrawRendererText" (ttf/draw-renderer-text t (double x) (double y)))
  nil)

(defn draw-surface-text!
  "TTF_DrawSurfaceText: draw a text object from a surface engine onto `surface` at x, y."
  [t x y surface]
  (core/check-bool "TTF_DrawSurfaceText" (ttf/draw-surface-text t (int x) (int y) surface))
  nil)

(defn gpu-draw-data
  "TTF_GetGPUTextDrawData: what to draw for a text object from a GPU engine, as a
  vector of {:atlas-texture :xy [[x y] ...] :uv [[u v] ...] :indices [i ...]
  :image-type}, one per atlas texture. Valid until the text or engine changes."
  [t]
  (loop [p (ttf/get-gpu-text-draw-data t) acc []]
    (if (ffi/null? p)
      acc
      (let [{:keys [atlas-texture xy uv num-vertices indices num-indices image-type next]}
            (ffi/read p ttf/gpu-atlas-draw-sequence)
            pairs (fn [q] (mapv (fn [i] [(ffi/read q :float (* 8 i)) (ffi/read q :float (+ 4 (* 8 i)))]) (range num-vertices)))]
        (recur next
               (conj acc {:atlas-texture atlas-texture
                          :xy (pairs xy)
                          :uv (pairs uv)
                          :indices (mapv #(ffi/read indices :int (* 4 %)) (range num-indices))
                          :image-type (core/unenum c/image-type-names image-type)}))))))

;; ---------------------------------------------------------------------------
;; substrings: hit testing and cursor movement
;; ---------------------------------------------------------------------------

(defn- decode-substring [p]
  (let [{:keys [flags offset length line-index cluster-index rect]} (ffi/read p ttf/sub-string)]
    {:offset offset :length length :line-index line-index :cluster-index cluster-index :rect rect
     :direction (core/unenum c/direction-names (bit-and flags (c/substring-flags :direction-mask)))
     :flags (core/unflag (dissoc c/substring-flags :direction-mask) flags)}))

(defn- substring-call [what f & args]
  (with-open [a (ffi/confined-arena)]
    (let [out (ffi/alloc a ttf/sub-string)]
      (core/check-bool what (apply f (concat args [out])))
      (decode-substring out))))

(defn substring
  "TTF_GetTextSubString: the cluster at UTF-8 byte `offset`, as {:offset :length
  :line-index :cluster-index :rect :direction :flags}; :flags may hold
  :text-start :line-start :line-end :text-end."
  [t offset]
  (substring-call "TTF_GetTextSubString" ttf/get-text-sub-string t (int offset)))

(defn substring-for-line
  "TTF_GetTextSubStringForLine: the whole of line `line`."
  [t line]
  (substring-call "TTF_GetTextSubStringForLine" ttf/get-text-sub-string-for-line t (int line)))

(defn substring-for-point
  "TTF_GetTextSubStringForPoint: the cluster nearest x, y — for clicking into text."
  [t x y]
  (substring-call "TTF_GetTextSubStringForPoint" ttf/get-text-sub-string-for-point t (int x) (int y)))

(defn substrings-for-range
  "TTF_GetTextSubStringsForRange: the clusters covering `length` bytes from
  `offset` (-1: to the end) — for drawing a selection."
  [t offset length]
  (with-outs [n :int]
    (let [p (core/check-ptr "TTF_GetTextSubStringsForRange" (ttf/get-text-sub-strings-for-range t (int offset) (int length) n))
          subs (mapv (fn [i] (decode-substring (ffi/read p :pointer (* i (ffi/sizeof :pointer))))) (range (ffi/read n :int)))]
      (core/free! p)
      subs)))

(defn- neighbor [what f t sub]
  (with-open [a (ffi/confined-arena)]
    (let [in (core/alloc-fields a ttf/sub-string (-> sub
                                                     (select-keys [:offset :length :line-index :cluster-index :rect])
                                                     (assoc :flags (bit-or (core/flags (dissoc c/substring-flags :direction-mask) (:flags sub))
                                                                           (core/enum c/direction (:direction sub :invalid))))))
          out (ffi/alloc a ttf/sub-string)]
      (core/check-bool what (f t in out))
      (decode-substring out))))

(defn previous-substring
  "TTF_GetPreviousTextSubString: the cluster before `sub` — for moving a cursor left."
  [t sub]
  (neighbor "TTF_GetPreviousTextSubString" ttf/get-previous-text-sub-string t sub))

(defn next-substring
  "TTF_GetNextTextSubString: the cluster after `sub` — for moving a cursor right."
  [t sub]
  (neighbor "TTF_GetNextTextSubString" ttf/get-next-text-sub-string t sub))
