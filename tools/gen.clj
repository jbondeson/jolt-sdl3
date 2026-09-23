#!/usr/bin/env jolt
;; tools/gen.clj — regenerate the raw SDL3 bindings from the installed headers.
;;
;;   jolt tools/gen.clj [SDL3 include dir] [project root]
;;
;; Reads every SDL3/SDL_*.h, and writes:
;;
;;   src/sdl3/raw/<header>.clj   one namespace per header: a jolt.ffi/defcfn for
;;                               every exported function, plus an ffi/layout for
;;                               every struct and union whose body is simple
;;                               enough (no anonymous nested bodies, no bitfields)
;;   src/sdl3/consts.clj         every enum and every selected #define group as
;;                               keyword -> value maps (values computed by
;;                               compiling a C program with clang), plus hint and
;;                               property name strings
;;   src/sdl3/raw/abi.clj        sizeof/offsetof of every generated layout, from C
;;   test/sdl3/abi_test.clj      asserts every generated layout matches the C ABI
;;
;; thunderchez did the same job for SDL2 with c2ffi's JSON; SDL3's headers are
;; regular enough (`extern SDL_DECLSPEC ret SDLCALL name(params);`) to read
;; directly, which keeps the generator dependency-free apart from clang.
(ns gen
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]))

;; ---------------------------------------------------------------------------
;; configuration
;; ---------------------------------------------------------------------------

(def skip-headers
  #{"SDL_begin_code.h" "SDL_close_code.h" "SDL_copying.h" "SDL_egl.h"
    "SDL_endian.h" "SDL_bits.h" "SDL_intrin.h" "SDL_main_impl.h" "SDL_oldnames.h"
    "SDL_opengl.h" "SDL_opengl_glext.h" "SDL_opengles.h" "SDL_opengles2.h"
    "SDL_opengles2_gl2.h" "SDL_opengles2_gl2ext.h" "SDL_opengles2_gl2platform.h"
    "SDL_opengles2_khrplatform.h" "SDL_platform_defines.h" "SDL_revision.h"
    "SDL_dlopennote.h" "SDL_assert.h" "SDL_test.h"})

;; #define groups to extract, by prefix, with the var name each becomes.
;; Enums are extracted automatically; these are the flag/constant sets SDL3
;; spells as #defines over a typedef'd integer.
(def define-groups
  [["SDL_INIT_"              "init-flags"]
   ["SDL_WINDOW_"            "window-flags"]
   ["SDL_WINDOWPOS_"         "windowpos"]
   ["SDLK_"                  "keycode"]
   ["SDL_KMOD_"              "keymod"]
   ["SDL_BUTTON_"            "mouse-button"]
   ["SDL_BLENDMODE_"         "blend-mode"]
   ["SDL_MESSAGEBOX_"        "messagebox-flags"]
   ["SDL_HAT_"               "joystick-hat"]
   ["SDL_HAPTIC_"            "haptic"]
   ["SDL_PEN_INPUT_"         "pen-input-flags"]
   ["SDL_SURFACE_"           "surface-flags"]
   ["SDL_TRAYENTRY_"         "tray-entry-flags"]
   ["SDL_GLOB_"              "glob-flags"]
   ["SDL_ALPHA_"             "alpha"]
   ["SDL_AUDIO_MASK_"        "audio-mask"]
   ["SDL_AUDIO_DEVICE_DEFAULT_" "audio-device-default"]
   ["SDL_GPU_TEXTUREUSAGE_"  "gpu-texture-usage"]
   ["SDL_GPU_BUFFERUSAGE_"   "gpu-buffer-usage"]
   ["SDL_GPU_COLORCOMPONENT_" "gpu-color-component"]
   ["SDL_GPU_SHADERFORMAT_"  "gpu-shader-format"]
   ["SDL_HINT_"              "hint"]
   ["SDL_PROP_"              "prop"]])

;; single #defines worth having, emitted as one map `misc`
(def define-singles
  ["SDL_TOUCH_MOUSEID" "SDL_PEN_MOUSEID" "SDL_MOUSE_TOUCHID" "SDL_PEN_TOUCHID"
   "SDL_SCANCODE_MASK" "SDL_KEYCODE_EXTENDED_MASK"
   "SDL_STANDARD_GRAVITY" "SDL_MAX_SINT32" "SDL_MAX_UINT32"])

;; C functions that wait, marked :blocking so the collector is not pinned while
;; they do. jolt refuses :blocking on a binding with a :string argument, so the
;; emitter drops the marker there (SDL_ShowSimpleMessageBox is the notable one).
(def blocking-re #"^(SDL_(Wait\w*|Delay\w*|LockMutex|LockRWLockForReading|LockRWLockForWriting|RunApp|SyncWindow|ReadProcess|ShowMessageBox|ShowSimpleMessageBox)|NET_WaitUntil\w*)$")

;; declared in the headers but exported only as macros over another symbol, so
;; there is nothing to bind: SDL_CreateThread(fn, name, data) expands to
;; SDL_CreateThreadRuntime(fn, name, data, begin, end)
(def macro-only-functions
  {"SDL_CreateThread" "a macro over SDL_CreateThreadRuntime"
   "SDL_CreateThreadWithProperties" "a macro over SDL_CreateThreadWithPropertiesRuntime"})

(def struct-name-overrides
  {"SDL_FRect" "frect" "SDL_FPoint" "fpoint" "SDL_FColor" "fcolor" "MIX_Point3D" "point-3d"})

;; camel-case splits the rule gets wrong for a few compounds
(def fn-name-overrides
  {"SDL_SetRenderVSync" "set-render-vsync"
   "SDL_GetRenderVSync" "get-render-vsync"
   "SDL_RenderTexture9Grid" "render-texture-9-grid"
   "SDL_RenderTexture9GridTiled" "render-texture-9-grid-tiled"
   "SDL_GetD3D9AdapterIndex" "get-d3d9-adapter-index"
   "SDL_SetiOSAnimationCallback" "set-ios-animation-callback"
   "SDL_SetiOSEventPump" "set-ios-event-pump"
   "SDL_HasAVX512F" "has-avx512f"
   "SDL_IOprintf" "io-printf"
   "SDL_SetWindowSurfaceVSync" "set-window-surface-vsync"
   "SDL_GetWindowSurfaceVSync" "get-window-surface-vsync"
   "SDL_IsDeXMode" "is-dex-mode"
   "TTF_GetHarfBuzzVersion" "get-harfbuzz-version"
   "TTF_GetFreeTypeVersion" "get-freetype-version"
   "MIX_SetTrack3DPosition" "set-track-3d-position"
   "MIX_GetTrack3DPosition" "get-track-3d-position"})

;; ---------------------------------------------------------------------------
;; naming
;; ---------------------------------------------------------------------------

(defn- upper? [c] (let [i (int c)] (<= 65 i 90)))
(defn- lower? [c] (let [i (int c)] (<= 97 i 122)))
(defn- digit? [c] (let [i (int c)] (<= 48 i 57)))

(defn split-camel
  "\"IOFromFile\" -> [\"IO\" \"From\" \"File\"]; \"GetRGBA\" -> [\"Get\" \"RGBA\"];
  \"Texture9Grid\" -> [\"Texture9\" \"Grid\"]."
  [s]
  (let [n (count s)]
    (loop [i 0 cur [] out []]
      (if (= i n)
        (if (seq cur) (conj out (apply str cur)) out)
        (let [c (nth s i)
              prev (when (pos? i) (nth s (dec i)))
              nxt (when (< (inc i) n) (nth s (inc i)))
              boundary? (and (upper? c) prev
                             (or (lower? prev) (digit? prev)
                                 (and (upper? prev) nxt (lower? nxt))))]
          (if (and boundary? (seq cur))
            (recur (inc i) [c] (conj out (apply str cur)))
            (recur (inc i) (conj cur c) out)))))))

(def ^:private library-prefix
  "The C prefixes stripped from names: SDL's own and its satellite libraries'."
  #"^(SDL|TTF|IMG|MIX|NET)_")

(defn c->clj
  "SDL_CreateWindowAndRenderer -> create-window-and-renderer; TTF_OpenFont ->
  open-font; windowID -> window-id."
  [s]
  (let [s (str/replace-first s library-prefix "")
        parts (remove str/blank? (str/split s #"_"))]
    (str/lower-case (str/join "-" (mapcat split-camel parts)))))

(defn struct->clj [cname]
  (or (struct-name-overrides cname) (c->clj cname)))

(defn member->kw
  "Enum/define member to keyword: strip prefix, lowercase, _ -> -. A member that
  would start with a digit (SDLK_0, SDL_GPU_SAMPLECOUNT_1) is prefixed with the
  group name, since :0 is not a readable keyword."
  [group-name prefix member]
  (let [tail (str/lower-case (str/replace (subs member (count prefix)) "_" "-"))
        tail (if (or (str/blank? tail) (digit? (first tail))) (str group-name "-" tail) tail)]
    (keyword tail)))

(defn common-prefix [names]
  (let [p (reduce (fn [acc s]
                    (let [n (min (count acc) (count s))]
                      (loop [i 0] (if (and (< i n) (= (nth acc i) (nth s i))) (recur (inc i)) (subs acc 0 i)))))
                  (first names) (rest names))
        cut (str/last-index-of p "_")]
    (if cut (subs p 0 (inc cut)) p)))

;; ---------------------------------------------------------------------------
;; header text
;; ---------------------------------------------------------------------------

(defn strip-comments [s]
  (-> s (str/replace #"(?s)/\*.*?\*/" " ") (str/replace #"//[^\n]*" "")))

(defn drop-preprocessor [s]
  (->> (str/split-lines s) (remove #(str/starts-with? (str/trim %) "#")) (str/join "\n")))

(def bare-annotations
  #{"SDL_PRINTF_FORMAT_STRING" "SDL_SCANF_FORMAT_STRING" "SDL_MALLOC" "SDL_ANALYZER_NORETURN"
    "SDL_NORETURN" "SDL_UNUSED" "SDL_RESTRICT" "SDL_DEPRECATED" "SDL_NODISCARD" "SDL_FALLTHROUGH"
    "SDL_INLINE" "SDL_FORCE_INLINE" "SDL_NO_THREAD_SAFETY_ANALYSIS" "SDL_DECLSPEC_NORETURN"})

(defn strip-annotations
  "Remove SDL_MALLOC, SDL_OUT_Z_CAP(n), SDL_ACQUIRE(m), SDL_PRINTF_VARARG_FUNC(2)
  and the other attribute macros, leaving types and names. Every all-caps SDL_
  macro WITH an argument list in a declaration is an attribute; the bare ones are
  listed, since a bare all-caps name can also be an enum member used as an array
  dimension (SDL_MESSAGEBOX_COLOR_COUNT)."
  [s]
  (-> s
      (str/replace #"\bSDL_[A-Z0-9_]+\s*\([^()]*\)" " ")
      (str/replace #"\bSDL_[A-Z0-9_]+\b" (fn [m] (if (bare-annotations m) " " m)))))

(defn squash [s] (str/trim (str/replace s #"\s+" " ")))

;; ---------------------------------------------------------------------------
;; type tables
;; ---------------------------------------------------------------------------

(def scalar-types
  {"void" :void "bool" :bool "int" :int "unsigned int" :uint "unsigned" :uint
   "long" :long "unsigned long" :ulong "long long" :int64 "unsigned long long" :uint64
   "short" :int16 "unsigned short" :uint16 "char" :char "unsigned char" :uint8 "signed char" :int8
   "float" :float "double" :double "size_t" :size_t "intptr_t" :iptr "uintptr_t" :uptr
   "Uint8" :uint8 "Sint8" :int8 "Uint16" :uint16 "Sint16" :int16 "Uint32" :uint32 "Sint32" :int32
   "Uint64" :uint64 "Sint64" :int64 "int8_t" :int8 "uint8_t" :uint8 "int16_t" :int16 "uint16_t" :uint16
   "int32_t" :int32 "uint32_t" :uint32 "int64_t" :int64 "uint64_t" :uint64 "wchar_t" :int})

(defn collect-typedefs
  "From all header text (comments stripped, preprocessor dropped): enum names,
  struct/union tags, scalar aliases, pointer typedefs, function-pointer typedefs."
  [text]
  (let [enums (set (map second (re-seq #"typedef enum (\w+)" text)))
        enums (into enums (map second (re-seq #"\}\s*(\w+)\s*;" (str/join " " (map first (re-seq #"typedef enum \w+\s*\{[^}]*\}\s*\w+\s*;" text))))))
        structs (set (map second (re-seq #"typedef (?:struct|union) (\w+)" text)))
        fnptrs (set (map second (re-seq #"typedef [^;(]*\(\s*SDLCALL\s*\*\s*(\w+)\s*\)" text)))
        ptrs (set (map second (re-seq #"typedef [^;(]*\*\s*(\w+)\s*;" text)))
        aliases (into {} (for [[_ base name] (re-seq #"typedef ((?:unsigned |signed )?\w+) (\w+)\s*;" text)
                              :when (scalar-types base)]
                          [name (scalar-types base)]))]
    {:enums enums :structs structs :fnptrs fnptrs :ptrs ptrs :aliases aliases}))

(defn normalize-type [s]
  (-> s (str/replace #"\b(const|volatile|struct|union|enum)\b" " ") squash))

(defn c-type->kw
  "Map a C type string to a jolt.ffi keyword. `pos` is :param, :ret or :field.
  Answers a keyword, a vector (nested layout, fields only), or [:skip reason]."
  [{:keys [enums structs fnptrs ptrs aliases layouts]} s pos]
  (let [raw (squash s)
        stars (count (filter #(= % \*) raw))
        array? (str/includes? raw "[")
        base (normalize-type (str/replace raw #"\*|\[[^\]]*\]" " "))
        const-char? (boolean (re-find #"^\s*const\s+char\s*\*\s*$" raw))]
    (cond
      (= raw "...") :&
      (= base "va_list") [:skip "va_list"]
      (or (pos? stars) array?)
      (cond
        (and (= stars 1) (= base "char") (not array?))
        (if (and const-char? (not= pos :field)) :string :pointer)
        :else :pointer)
      (scalar-types base) (scalar-types base)
      (aliases base) (aliases base)
      (enums base) :int
      (fnptrs base) :pointer
      ;; Vulkan handles (VkInstance, VkSurfaceKHR, ...) are pointer-sized on 64-bit targets
      (str/starts-with? base "Vk") :pointer
      (ptrs base) :pointer
      (structs base) (cond
                       (and (= pos :field) (get layouts base)) (get layouts base)
                       (get layouts base) [:by-value (get layouts base)]
                       :else [:skip (str "struct by value: " base)])
      :else [:skip (str "unknown type: " base)])))

;; ---------------------------------------------------------------------------
;; functions
;; ---------------------------------------------------------------------------

(defn parse-param [s]
  (let [s (squash s)]
    (cond
      (= s "void") nil
      (= s "...") {:type "..." :name "&"}
      :else
      (let [[_ arr-name] (re-find #"(\w+)\s*\[[^\]]*\]\s*$" s)
            [_ typ name] (re-find #"^(.*?)\s*(\w+)\s*(?:\[[^\]]*\])?\s*$" s)]
        (if arr-name
          {:type (str (str/replace s #"\s*\w+\s*\[[^\]]*\]\s*$" "") " *") :name arr-name}
          {:type typ :name name})))))

(defn parse-functions [text]
  (for [[_ ret name params] (re-seq #"extern SDL_DECLSPEC (.+?)\s*SDLCALL (\w+)\s*\(([^)]*)\)\s*;" text)]
    {:c name :ret (squash ret)
     :params (vec (keep parse-param (str/split params #",")))}))

;; ---------------------------------------------------------------------------
;; structs
;; ---------------------------------------------------------------------------

(defn parse-field-decl
  "One `type name;` field, possibly `int x, y;`, `float data[3];`,
  `void (SDLCALL *close)(void *u);`. Answers a vector of {:type :name :dim}."
  [s]
  (let [s (squash s)]
    (cond
      (str/blank? s) []
      (re-find #"\(\s*SDLCALL\s*\*\s*(\w+)\s*\)" s)
      [{:type "void *" :name (second (re-find #"\(\s*SDLCALL\s*\*\s*(\w+)\s*\)" s))}]
      (str/includes? s ":") [{:bitfield true}]
      :else
      (let [[_ dim] (re-find #"\[\s*([^\]]+?)\s*\]\s*$" s)
            s (str/replace s #"\s*\[[^\]]*\]\s*$" "")
            [_ typ names] (re-find #"^(.*?)\s*(\w+(?:\s*,\s*\w+)*)$" s)]
        (for [n (map str/trim (str/split names #","))]
          (cond-> {:type typ :name n} dim (assoc :dim dim)))))))

(defn parse-structs
  "Every `typedef struct|union Name { simple body } Name;` in text, in order, and
  every `struct Name { simple body };` declared apart from its typedef (SDL_Surface)."
  [text]
  (concat
   (for [[_ kind tag body name] (re-seq #"typedef (struct|union) (\w+)\s*\{([^{}]*)\}\s*(\w+)\s*;" text)]
     {:c name :tag tag :kind (keyword kind)
      :fields (vec (mapcat parse-field-decl (str/split body #";")))})
   (for [[_ kind tag body] (re-seq #"(?:^|[^\w])(struct|union) (\w+)\s*\{([^{}]*)\}\s*;" text)]
     {:c tag :tag tag :kind (keyword kind)
      :fields (vec (mapcat parse-field-decl (str/split body #";")))})))

;; ---------------------------------------------------------------------------
;; enums and defines
;; ---------------------------------------------------------------------------

(defn parse-enums [text]
  (for [[_ tag body name] (re-seq #"typedef enum (\w+)\s*\{([^}]*)\}\s*(\w+)\s*;" text)
        :let [b (-> body (str/replace #"\([^()]*\)" "") (str/replace #"\([^()]*\)" ""))
              members (vec (map second (re-seq #"(?:^|,)\s*([A-Za-z_]\w*)" b)))]
        :when (or (seq members) (binding [*out* *err*] (println "WARNING empty enum" name) false))]
    {:c name :members members}))

(defn parse-defines
  "Object-like #defines: name -> value text (function-like macros excluded)."
  [comment-stripped]
  (into {} (for [[_ name value] (re-seq #"(?m)^\s*#\s*define\s+((?:SDL|TTF|IMG|MIX|NET)\w*)[ \t]+([^\n]*)$" comment-stripped)
                 :when (not (re-find #"\(" (subs (str name " ") 0 (inc (count name)))))]
             [name (str/trim value)])))

(defn define-kind [defines name]
  (loop [v (get defines name) seen #{}]
    (cond
      (nil? v) :int
      (str/starts-with? v "\"") :string
      (and (re-find #"^\w+$" v) (contains? defines v) (not (seen v))) (recur (get defines v) (conj seen v))
      :else :int)))

;; ---------------------------------------------------------------------------
;; C oracle: compile one program that prints every constant, size and offset
;; ---------------------------------------------------------------------------

(defn run-c-oracle [include-dir includes int-names string-names structs tmp-dir]
  (let [lines (concat
               (map #(str "#include <" % ">") includes)
               ["#include <stdio.h>" "#include <stddef.h>" "int main(void){"]
               (for [n int-names] (str "printf(\"" n " %lld\\n\", (long long)(" n "));"))
               (for [n string-names] (str "printf(\"" n " %s\\n\", (const char*)(" n "));"))
               (for [{:keys [c fields]} structs]
                 (str "printf(\"sizeof " c " %zu\\n\", sizeof(" c "));"
                      (apply str (for [{:keys [name]} fields]
                                   (str "printf(\"offsetof " c " " name " %zu\\n\", offsetof(" c ", " name "));")))))
               ["return 0;}"])
        src (str tmp-dir "/sdl3_oracle.c")
        bin (str tmp-dir "/sdl3_oracle")]
    (io/make-parents src)
    (spit src (str/join "\n" lines))
    (let [cc (sh/sh "clang" "-I" include-dir "-Wno-everything" "-o" bin src)]
      (when-not (zero? (:exit cc))
        (binding [*out* *err*] (println (:err cc)))
        (throw (ex-info "oracle compile failed" {:src src}))))
    (let [run (sh/sh bin)]
      (when-not (zero? (:exit run)) (throw (ex-info "oracle run failed" run)))
      (reduce (fn [acc line]
                (let [[k & more] (str/split line #" " 2)]
                  (cond
                    (= k "sizeof") (let [[c n] (str/split (first more) #" ")] (assoc-in acc [:sizes c] (Long/parseLong n)))
                    (= k "offsetof") (let [[c f n] (str/split (first more) #" ")] (assoc-in acc [:offsets c f] (Long/parseLong n)))
                    :else (assoc-in acc [:values k] (first more)))))
              {} (str/split-lines (:out run))))))

;; ---------------------------------------------------------------------------
;; layouts
;; ---------------------------------------------------------------------------

(defn resolve-layouts
  "Turn parsed structs into literal ffi/layout descriptors, recursively inlining
  nested structs. Answers {:layouts {cname descriptor} :skipped {cname reason}}."
  [types structs values]
  (let [by-name (into {} (map (juxt :c identity) structs))
        state (atom {:layouts {} :skipped {}})]
    (letfn [(dim-count [d]
              (cond (re-find #"^\d+$" d) (Long/parseLong d)
                    (get values d) (Long/parseLong (get values d))
                    :else nil))
            (resolve! [cname stack]
              (let [{:keys [layouts skipped]} @state]
                (cond
                  (contains? layouts cname) (get layouts cname)
                  (contains? skipped cname) nil
                  (contains? stack cname) nil
                  :else
                  (let [{:keys [kind fields]} (get by-name cname)
                        fields' (reduce
                                 (fn [acc {:keys [type name dim bitfield]}]
                                   (if (or (nil? acc) bitfield)
                                     nil
                                     (let [t (let [base (normalize-type (str/replace type #"\*|\[[^\]]*\]" " "))]
                                               (if (and (not (str/includes? type "*")) (contains? by-name base))
                                                 (or (resolve! base (conj stack cname)) [:skip (str "nested " base)])
                                                 (c-type->kw types type :field)))
                                           t (cond
                                               (and (vector? t) (= :skip (first t))) t
                                               dim (if-let [n (dim-count dim)] [:array t n] [:skip (str "array dim " dim)])
                                               :else t)]
                                       (if (and (vector? t) (= :skip (first t)))
                                         (do (swap! state assoc-in [:skipped cname] (str name ": " (second t))) nil)
                                         (conj acc [(keyword (c->clj name)) t])))))
                                 [] fields)]
                    (if (and fields' (seq fields'))
                      (let [d [kind fields']]
                        (swap! state assoc-in [:layouts cname] d)
                        d)
                      (do (when-not (get-in @state [:skipped cname])
                            (swap! state assoc-in [:skipped cname] "empty"))
                          nil))))))]
      (doseq [{:keys [c]} structs] (resolve! c #{}))
      @state)))

;; ---------------------------------------------------------------------------
;; emit
;; ---------------------------------------------------------------------------

(def header-template
  ";; GENERATED by tools/gen.clj from the %s headers — do not edit by hand.\n;; Regenerate with `jolt gen` (see deps.edn :tasks).\n;; Reproduces declarations from %s, Copyright (C) %s\n;; Sam Lantinga <slouken@libsdl.org>, zlib license; see LICENSE.\n")

(defn file-header [{:keys [title library years]}]
  (format header-template title library years))

;; ---------------------------------------------------------------------------
;; libraries
;; ---------------------------------------------------------------------------

(def libraries
  "SDL and the satellite libraries bound alongside it. Every library found under
  the include directory is generated; a missing satellite is skipped, keeping
  whatever was generated for it before."
  [{:id :sdl3 :title "SDL3" :library "Simple DirectMedia Layer" :years "1997-2026"
    :dir "SDL3" :prefix "SDL_" :include ["SDL3/SDL.h"]
    :module-per-header true
    :consts-ns "sdl3.consts" :consts-file "src/sdl3/consts.clj"
    :define-groups define-groups :string-groups #{"SDL_HINT_" "SDL_PROP_"}
    :singles define-singles}
   {:id :ttf :title "SDL_ttf" :library "SDL_ttf" :years "2001-2025"
    :dir "SDL3_ttf" :prefix "TTF_" :include ["SDL3_ttf/SDL_ttf.h" "SDL3_ttf/SDL_textengine.h"]
    :headers ["SDL_ttf.h" "SDL_textengine.h"] :module "ttf"
    :consts-ns "sdl3.consts.ttf" :consts-file "src/sdl3/consts/ttf.clj"
    :define-groups [["TTF_STYLE_" "style"] ["TTF_FONT_WEIGHT_" "font-weight"]
                    ["TTF_SUBSTRING_" "substring-flags"] ["TTF_PROP_" "prop"]]
    :string-groups #{"TTF_PROP_"}
    :singles ["SDL_TTF_MAJOR_VERSION" "SDL_TTF_MINOR_VERSION" "SDL_TTF_MICRO_VERSION"]}
   {:id :image :title "SDL_image" :library "SDL_image" :years "1997-2026"
    :dir "SDL3_image" :prefix "IMG_" :include ["SDL3_image/SDL_image.h"]
    :headers ["SDL_image.h"] :module "image"
    :consts-ns "sdl3.consts.image" :consts-file "src/sdl3/consts/image.clj"
    :define-groups [["IMG_PROP_" "prop"]]
    :string-groups #{"IMG_PROP_"}
    :singles ["SDL_IMAGE_MAJOR_VERSION" "SDL_IMAGE_MINOR_VERSION" "SDL_IMAGE_MICRO_VERSION"]}
   {:id :mixer :title "SDL_mixer" :library "SDL_mixer" :years "1997-2026"
    :dir "SDL3_mixer" :prefix "MIX_" :include ["SDL3_mixer/SDL_mixer.h"]
    :headers ["SDL_mixer.h"] :module "mixer"
    :consts-ns "sdl3.consts.mixer" :consts-file "src/sdl3/consts/mixer.clj"
    :define-groups [["MIX_DURATION_" "duration"] ["MIX_PROP_" "prop"]]
    :string-groups #{"MIX_PROP_"}
    :singles ["SDL_MIXER_MAJOR_VERSION" "SDL_MIXER_MINOR_VERSION" "SDL_MIXER_MICRO_VERSION"]}
   {:id :net :title "SDL_net" :library "SDL_net" :years "1997-2026"
    :dir "SDL3_net" :prefix "NET_" :include ["SDL3_net/SDL_net.h"]
    :headers ["SDL_net.h"] :module "net"
    :consts-ns "sdl3.consts.net" :consts-file "src/sdl3/consts/net.clj"
    :define-groups [["NET_PROP_" "prop"]]
    :string-groups #{"NET_PROP_"}
    :singles ["SDL_NET_MAJOR_VERSION" "SDL_NET_MINOR_VERSION" "SDL_NET_MICRO_VERSION"]}])

;; ---------------------------------------------------------------------------
;; emit
;; ---------------------------------------------------------------------------

(defn header-module [h] (str/lower-case (str/replace (str/replace h #"^SDL_" "") #"\.h$" "")))

(defn emit-fn [types core-names {:keys [c ret params]}]
  (let [name (or (fn-name-overrides c) (c->clj c))
        rt (c-type->kw types ret :ret)
        pts (map #(c-type->kw types (:type %) :param) params)
        skip (or (when-let [why (macro-only-functions c)] [:skip why])
                 (first (filter #(and (vector? %) (= :skip (first %))) (cons rt pts))))]
    (if skip
      {:skip [c (second skip)]}
      (let [argtypes (vec pts)
            argnames (vec (map #(symbol (c->clj (:name %))) (remove #(= "&" (:name %)) params)))
            ;; jolt hands an aggregate return back through a caller-owned first argument
            argnames (if (and (vector? rt) (= :by-value (first rt))) (into [(quote out)] argnames) argnames)
            argnames (if (some #{:&} argtypes) (conj argnames '& 'varargs) argnames)
            blocking? (and (re-find blocking-re c) (not (some #{:string :&} argtypes)))
            sig (str (squash ret) " " c "(" (str/join ", " (map #(str (:type %) (when-not (= "&" (:name %)) (str " " (:name %))) ) params)) ")")
            sig (str/replace sig #"\(\s*\)" "(void)")]
        {:name name
         :code (str "(ffi/defcfn " name "\n  " (pr-str sig)
                    "\n  {:arglists '(" (pr-str argnames) ") :sdl/c " (pr-str c) " :sdl/args " (pr-str argtypes) " :sdl/ret " (pr-str rt) "}"
                    "\n  " (pr-str c) " " (pr-str argtypes) " " (pr-str rt) (when blocking? " :blocking") ")\n")}))))

(defn emit-module [lib types core-names module cheader fns structs layouts]
  (let [emitted (map #(emit-fn types core-names %) fns)
        fn-names (map :name (remove :skip emitted))
        struct-defs (for [{:keys [c]} structs :when (get layouts c)]
                      [(struct->clj c) c (get layouts c)])
        taken (set fn-names)
        struct-defs (map (fn [[n c d]] [(if (taken n) (str n "-layout") n) c d]) struct-defs)
        all-names (concat fn-names (map first struct-defs))
        dups (->> all-names frequencies (filter #(> (val %) 1)) (map key))
        excludes (sort (filter core-names all-names))]
    (when (seq dups) (binding [*out* *err*] (println "WARNING duplicate names in" module ":" (vec dups))))
    {:skipped (keep :skip emitted)
     :code (str (file-header lib)
                "(ns sdl3.raw." module
                "\n  \"Raw bindings for " cheader ": one jolt.ffi/defcfn per exported C function,"
                "\n  named by kebab-casing the C name without its " (:prefix lib) " prefix, and one ffi/layout per"
                "\n  struct. Nothing here checks errors or converts values — see the sdl3.* namespaces.\""
                (when (seq excludes) (str "\n  (:refer-clojure :exclude [" (str/join " " excludes) "])"))
                "\n  (:require [jolt.ffi :as ffi]))\n\n"
                (when (seq struct-defs)
                  (str ";; ---- structs -------------------------------------------------------------\n\n"
                       (apply str (for [[n c d] struct-defs]
                                    (str "(def " n "\n  \"ffi/layout of " c ".\"\n  (ffi/layout " (pr-str d) "))\n\n")))))
                ";; ---- functions -----------------------------------------------------------\n\n"
                (str/join "\n" (map :code (remove :skip emitted))))
     :layout-vars (map (fn [[n c _]] [c (str "sdl3.raw." module "/" n)]) struct-defs)}))

(defn emit-consts [lib enum-maps define-maps]
  (str (file-header lib)
       "(ns " (:consts-ns lib) "\n  \"Every " (:title lib) " enum and flag set as a map from keyword to value, plus"
       (if (= :sdl3 (:id lib)) " hint and\n  " "\n  ") "property name strings. `-names` maps invert an enum (first-declared name wins\n  for aliased values). Values were computed by compiling the C headers.\"\n  (:refer-clojure :exclude [keys]))\n\n"
       (apply str
              (for [{:keys [var c prefix members ordered]} enum-maps]
                (str "(def " var "\n  \"" c " (" prefix "*).\"\n  " (pr-str members) ")\n\n"
                     "(def " var "-names\n  \"" c " value -> keyword (the first-declared name of an aliased value).\"\n  "
                     (pr-str (reduce (fn [m [k v]] (if (contains? m v) m (assoc m v k))) {} ordered)) ")\n\n")))
       (apply str
              (for [{:keys [var prefix members]} define-maps]
                (str "(def " var "\n  \"#define " prefix "*.\"\n  " (pr-str members) ")\n\n")))))

(defn emit-abi [oracle layouts]
  (str (file-header (first libraries))
       "(ns sdl3.raw.abi\n  \"sizeof and offsetof of every generated layout, as the C compiler reports them.\n  test/sdl3/abi_test.clj checks each layout against this.\")\n\n"
       "(def structs\n  " (pr-str (into (sorted-map)
                                    (for [[c _] layouts]
                                      [c {:size (get-in oracle [:sizes c])
                                          :offsets (into (sorted-map) (for [[f o] (get-in oracle [:offsets c])] [(keyword (c->clj f)) o]))}])))
       ")\n"))

(defn emit-abi-test [layout-vars]
  (let [nses (sort (distinct (map #(first (str/split (second %) #"/")) layout-vars)))]
    (str (file-header (first libraries))
         "(ns sdl3.abi-test\n  (:require [clojure.test :refer [deftest is testing]]\n            [jolt.ffi :as ffi]\n            [sdl3.raw.abi :as abi]\n"
         (str/join "\n" (map #(str "            [" % "]") nses)) "))\n\n"
         "(def layouts\n  {" (str/join "\n   " (for [[c v] (sort layout-vars)] (str (pr-str c) " " v))) "})\n\n"
         "(deftest layouts-match-c-abi\n  (doseq [[cname layout] layouts]\n    (testing cname\n      (let [{:keys [size offsets]} (get abi/structs cname)]\n        (is (= size (ffi/layout-size layout)) (str cname \" size\"))\n        (doseq [[field off] offsets]\n          (is (= off (ffi/field-offset layout field)) (str cname \".\" (name field))))))))\n")))

;; ---------------------------------------------------------------------------
;; main
;; ---------------------------------------------------------------------------

(defn- read-library
  "Everything the generator needs from one library's headers, before the oracle runs."
  [include-dir {:keys [dir headers define-groups string-groups singles] :as lib}]
  (let [hdir (io/file include-dir dir)
        headers (or headers
                    (->> (.listFiles hdir) (map #(.getName %))
                         (filter #(re-find #"^SDL_\w+\.h$" %))
                         (remove #(or (skip-headers %) (str/starts-with? % "SDL_test")))
                         sort))
        texts (into {} (for [h headers] [h (strip-comments (slurp (io/file hdir h)))]))
        clean (into {} (for [[h t] texts] [h (strip-annotations (drop-preprocessor t))]))
        enums (mapcat #(parse-enums (get clean %)) headers)
        defines (apply merge (map #(parse-defines (get texts %)) headers))
        enum-members (set (mapcat :members enums))
        groups (for [[prefix var] define-groups]
                 (let [names (sort (filter #(and (str/starts-with? % prefix) (not (enum-members %))) (keys defines)))]
                   {:var var :prefix prefix :names names
                    :kind (if (string-groups prefix) :string :int)}))]
    (assoc lib
           :headers headers :texts texts :clean clean :enums enums :groups groups
           :singles (filter defines singles)
           :enum-members enum-members
           :structs-by-header (into {} (for [h headers] [h (vec (parse-structs (get clean h)))])))))

(defn- present? [include-dir {:keys [dir include]}]
  (.exists (io/file include-dir (first include))))

(defn -main [& [include-dir root]]
  (let [include-dir (or include-dir
                        (first (filter #(.exists (io/file % "SDL3/SDL.h"))
                                       ["/opt/homebrew/include" "/usr/local/include" "/usr/include"]))
                        (throw (ex-info "SDL3 headers not found; pass the include dir" {})))
        root (or root ".")
        libs (vec (for [lib libraries
                        :when (or (= :sdl3 (:id lib))
                                  (present? include-dir lib)
                                  (do (println "skipping" (:title lib) "— headers not found under" include-dir) false))]
                    (read-library include-dir lib)))
        all-clean (str/join "\n" (mapcat (comp vals :clean) libs))
        types (collect-typedefs all-clean)
        core-names (set (map (comp str key) (ns-publics 'clojure.core)))
        all-structs (vec (mapcat #(mapcat (:structs-by-header %) (:headers %)) libs))
        int-names (distinct (mapcat (fn [{:keys [enum-members groups singles]}]
                                      (concat enum-members (mapcat :names (filter #(= :int (:kind %)) groups)) singles))
                                    libs))
        string-names (distinct (mapcat (fn [{:keys [groups]}] (mapcat :names (filter #(= :string (:kind %)) groups))) libs))
        _ (doseq [{:keys [title headers enums structs-by-header]} libs]
            (println title "— headers:" (count headers) "enums:" (count enums)
                     "structs parsed:" (count (mapcat val structs-by-header))))
        ;; one C program answers every constant and layout, for all the libraries at once
        oracle (run-c-oracle include-dir (mapcat :include libs) int-names string-names
                             (remove #(some :bitfield (:fields %)) all-structs)
                             (str root "/target/gen"))
        values (:values oracle)
        {:keys [layouts skipped]} (resolve-layouts (assoc types :layouts {}) all-structs values)
        _ (println "layouts:" (count layouts) "skipped structs:" (count skipped))
        _ (doseq [[c why] (sort skipped)] (println "  skip struct" c "—" why))
        types (assoc types :layouts layouts)
        per-lib
        (vec
         (for [{:keys [title headers texts clean structs-by-header module-per-header module enums groups singles]
                :as lib} libs]
           (let [fns-by-header (into {} (for [h headers] [h (vec (parse-functions (get clean h)))]))
                 declared (reduce + (map #(count (re-seq #"extern SDL_DECLSPEC" (get texts %))) headers))
                 parsed (reduce + (map count (vals fns-by-header)))
                 _ (println title "— functions declared:" declared "parsed:" parsed)
                 _ (let [want (set (mapcat #(map second (re-seq #"SDLCALL (\w+)\s*\(" (get clean %))) headers))
                         got (set (map :c (mapcat val fns-by-header)))]
                     (doseq [n (sort (remove got want))] (println "  unparsed decl:" n)))
                 ;; the core library gets one namespace per header; a satellite, one in all
                 units (if module-per-header
                         (for [h headers] [(header-module h) h (get fns-by-header h) (get structs-by-header h)])
                         [[module (str/join ", " headers) (mapcat fns-by-header headers) (mapcat structs-by-header headers)]])
                 results (vec (for [[mod cheader fns structs] units
                                    :when (or (seq fns) (some #(get layouts (:c %)) structs))]
                                (let [r (emit-module lib types core-names mod cheader fns structs layouts)
                                      path (str root "/src/sdl3/raw/" (str/replace mod "-" "_") ".clj")]
                                  (io/make-parents path)
                                  (spit path (:code r))
                                  (assoc r :module mod))))
                 enum-maps (for [{:keys [c members]} enums]
                             (let [prefix (common-prefix members) var (c->clj c)]
                               {:c c :var var :prefix prefix
                                :ordered (vec (for [m members] [(member->kw var prefix m) (Long/parseLong (get values m))]))
                                :members (into (sorted-map) (for [m members] [(member->kw var prefix m) (Long/parseLong (get values m))]))}))
                 define-maps (concat
                              (for [{:keys [var prefix names kind]} groups]
                                {:var var :prefix prefix
                                 :members (into (sorted-map) (for [n names] [(member->kw var prefix n)
                                                                             (if (= kind :string) (get values n) (Long/parseLong (get values n)))]))})
                              (when (seq singles)
                                [{:var "misc" :prefix (:prefix lib)
                                  :members (into (sorted-map) (for [n singles] [(keyword (c->clj n)) (Long/parseLong (get values n))]))}]))
                 cpath (str root "/" (:consts-file lib))]
             (io/make-parents cpath)
             (spit cpath (emit-consts lib enum-maps define-maps))
             (println title "— modules written:" (count results) "functions bound:"
                      (- parsed (count (mapcat :skipped results))) "skipped:" (count (mapcat :skipped results))
                      "consts: enums" (count enum-maps) "define groups" (count define-maps))
             (doseq [[c why] (mapcat :skipped results)] (println "  skip fn" c "—" why))
             results)))
        layout-vars (mapcat :layout-vars (apply concat per-lib))]
    (spit (str root "/src/sdl3/raw/abi.clj") (emit-abi oracle layouts))
    (let [p (str root "/test/sdl3/abi_test.clj")] (io/make-parents p) (spit p (emit-abi-test layout-vars)))))

(apply -main *command-line-args*)
