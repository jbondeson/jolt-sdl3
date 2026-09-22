(ns sdl3.core
  "The base of the idiomatic layer: SDL's error convention as exceptions, keyword
  flags, init/quit, version and hints.

  Every sdl3.* namespace wraps the generated sdl3.raw.* bindings the same way:

    - a C function that answers `bool` for success raises an ExceptionInfo
      carrying SDL_GetError when it answers false; one that answers a pointer
      raises on NULL. The exception's data holds :sdl/fn and :sdl/error.
    - flag arguments take keywords from sdl3.consts (or a collection of them, or
      a plain integer), and flag results come back as sets of keywords.
    - pointers stay plain jolt pointers (integers): a window, a renderer or a
      texture is the address SDL handed out, freed with the matching destroy!.

  Predicates and lookups whose false or NULL is an answer rather than a failure
  (SDL_PollEvent, SDL_GetWindowFromID) are wrapped as such; see each var's doc.

      (require '[sdl3.core :as sdl])
      (sdl/with-sdl [:video]
        ...)"
  (:require [jolt.ffi :as ffi]
            [clojure.string :as str]
            [sdl3.consts :as c]
            [sdl3.raw.init :as init]
            [sdl3.raw.error :as error]
            [sdl3.raw.version :as version]
            [sdl3.raw.hints :as hints]
            [sdl3.raw.platform :as platform]
            [sdl3.raw.stdinc :as stdinc]))

;; ---------------------------------------------------------------------------
;; errors
;; ---------------------------------------------------------------------------

(defn error
  "SDL_GetError: the last error message set on this thread, or \"\"."
  []
  (or (error/get-error) ""))

(defn clear-error!
  "SDL_ClearError."
  []
  (error/clear-error)
  nil)

(defn sdl-error
  "An ExceptionInfo for a failed call to `what` (a C symbol or a description),
  carrying SDL_GetError as :sdl/error."
  [what]
  (let [msg (error)]
    (ex-info (str what " failed: " (if (str/blank? msg) "(SDL set no error message)" msg))
             {:sdl/fn what :sdl/error msg})))

(defn check-bool
  "Answer true when `ok` (a C bool result) is true, else raise sdl-error for `what`."
  [what ok]
  (if ok true (throw (sdl-error what))))

(defn check-ptr
  "Answer `p` when it is a non-NULL pointer, else raise sdl-error for `what`."
  [what p]
  (if (and p (not (ffi/null? p))) p (throw (sdl-error what))))

(defn nullable
  "Answer `p`, or nil when it is NULL — for lookups where NULL means absent."
  [p]
  (when (and p (not (ffi/null? p))) p))

;; ---------------------------------------------------------------------------
;; defsdl — wrap a raw binding with the error convention
;; ---------------------------------------------------------------------------

(defmacro defsdl
  "Define `name` as the raw binding `raw-var` (a sdl3.raw.* var) with SDL's error
  convention applied, keeping its argument names and C signature in the docstring.
  A :bool result raises on false and a :pointer result raises on NULL, unless:

    :pred true      the bool is an answer (SDL_HasEvent), not a status
    :nullable true  NULL is an answer (SDL_GetWindowFromID), answered as nil
    :doc \"...\"      prepended to the generated docstring"
  [name raw-var & {:keys [doc pred nullable]}]
  (let [v (resolve raw-var)
        _ (when-not v (throw (ex-info (str "defsdl: cannot resolve " raw-var) {:var raw-var})))
        m (meta v)
        args (first (:arglists m))
        ret (:sdl/ret m)
        c (:sdl/c m)
        variadic? (boolean (some #{'&} args))
        fixed (vec (take-while #(not= '& %) args))
        call (if variadic?
               `(apply ~raw-var ~@fixed ~'varargs)
               `(~raw-var ~@fixed))
        body (cond
               (and (= ret :bool) (not pred)) `(check-bool ~c ~call)
               (and (= ret :pointer) (not nullable)) `(check-ptr ~c ~call)
               (and (= ret :pointer) nullable) `(nullable ~call)
               ;; a void C function answers Chez's void object; answer nil instead
               (= ret :void) `(do ~call nil)
               :else call)
        docstring (str (when doc (str doc "\n\n  ")) (:doc m))]
    `(defn ~(with-meta name {:sdl/c c})
       ~docstring
       ~(if variadic? (conj fixed '& 'varargs) fixed)
       ~body)))

;; ---------------------------------------------------------------------------
;; flags and enums
;; ---------------------------------------------------------------------------

(defn flags
  "OR together `fs` against `table`, a sdl3.consts map: a keyword, an integer, nil
  (0), or a collection of any of those, nested as deep as you like.

      (flags c/window-flags [:resizable :high-pixel-density]) ;=> 8224"
  [table fs]
  (cond
    (nil? fs) 0
    (integer? fs) fs
    (keyword? fs) (or (get table fs)
                      (throw (ex-info (str "unknown flag " fs) {:flag fs :known (sort (keys table))})))
    (coll? fs) (reduce (fn [acc f] (bit-or acc (flags table f))) 0 fs)
    :else (throw (ex-info (str "not a flag: " (pr-str fs)) {:flag fs}))))

(defn unflag
  "Decode integer `v` into the set of single-bit keywords of `table` present in it.
  Composite aliases in the table (SDL_KMOD_CTRL = LCTRL | RCTRL) are skipped."
  [table v]
  (into #{} (for [[k b] table
                  :when (and (pos? b) (zero? (bit-and b (dec b))) (= b (bit-and v b)))]
              k)))

(defn enum
  "The integer for keyword `k` in `table` (a sdl3.consts enum map); an integer
  passes through."
  [table k]
  (cond
    (integer? k) k
    (keyword? k) (or (get table k)
                     (throw (ex-info (str "unknown value " k) {:value k :known (sort (keys table))})))
    :else (throw (ex-info (str "not an enum value: " (pr-str k)) {:value k}))))

(defn unenum
  "The keyword for integer `v` in `names` (a sdl3.consts *-names map), or `v`
  itself when the table has no name for it."
  [names v]
  (get names v v))

;; ---------------------------------------------------------------------------
;; out-parameters
;; ---------------------------------------------------------------------------

(defmacro with-outs
  "ffi/with-out for several cells at once: (with-outs [w :int h :int] ...) binds
  w and h to freshly allocated, zeroed scalars freed when the body ends."
  [bindings & body]
  (if (empty? bindings)
    `(do ~@body)
    `(ffi/with-out [~(first bindings) ~(second bindings)]
       (with-outs ~(vec (drop 2 bindings)) ~@body))))

;; ---------------------------------------------------------------------------
;; init / quit
;; ---------------------------------------------------------------------------

(defn init!
  "SDL_Init. Subsystems are keywords from sdl3.consts/init-flags — :video :audio
  :events :joystick :gamepad :haptic :sensor :camera — given as separate
  arguments or a collection. Raises on failure; answers nil."
  [& subsystems]
  (check-bool "SDL_Init" (init/init (flags c/init-flags subsystems)))
  nil)

(defn init-sub-system!
  "SDL_InitSubSystem — init! for subsystems added after the first init!."
  [& subsystems]
  (check-bool "SDL_InitSubSystem" (init/init-sub-system (flags c/init-flags subsystems)))
  nil)

(defn quit-sub-system!
  "SDL_QuitSubSystem."
  [& subsystems]
  (init/quit-sub-system (flags c/init-flags subsystems))
  nil)

(defn was-init
  "SDL_WasInit: the set of initialized subsystem keywords."
  []
  (unflag c/init-flags (init/was-init 0)))

(defn quit!
  "SDL_Quit: shut every subsystem down. Safe to call more than once."
  []
  (init/quit)
  nil)

(defmacro with-sdl
  "Run body with the given subsystems initialized, calling SDL_Quit on the way out
  however the body ends: (with-sdl [:video] ...)."
  [subsystems & body]
  `(do (init! ~subsystems)
       (try ~@body (finally (quit!)))))

(defn set-app-metadata!
  "SDL_SetAppMetadata: the app's name, version and identifier (reverse-DNS), shown
  by the OS in about boxes and audio mixers. Call before init!."
  [app-name app-version app-identifier]
  (check-bool "SDL_SetAppMetadata" (init/set-app-metadata app-name app-version app-identifier))
  nil)

(defn set-app-metadata-property!
  "SDL_SetAppMetadataProperty: `prop` is a keyword from sdl3.consts/prop
  (:app-metadata-name-string, :app-metadata-url-string, ...) or the SDL string."
  [prop value]
  (check-bool "SDL_SetAppMetadataProperty"
              (init/set-app-metadata-property (if (keyword? prop) (enum c/prop prop) prop) value))
  nil)

;; ---------------------------------------------------------------------------
;; version and platform
;; ---------------------------------------------------------------------------

(defn version
  "The linked SDL library's version: {:major :minor :micro :string}."
  []
  (let [v (version/get-version)
        major (quot v 1000000)
        minor (mod (quot v 1000) 1000)
        micro (mod v 1000)]
    {:major major :minor minor :micro micro :string (str major "." minor "." micro)}))

(defsdl revision version/get-revision
  :doc "The linked SDL library's source revision, as a string.")

(defsdl platform platform/get-platform
  :doc "The platform name SDL was built for: \"macOS\", \"Linux\", \"Windows\", ...")

;; ---------------------------------------------------------------------------
;; hints
;; ---------------------------------------------------------------------------

(defn- hint-name [h]
  (if (keyword? h) (enum c/hint h) h))

(defn- hint-value [v]
  (cond (true? v) "1" (false? v) "0" :else (str v)))

(defn set-hint!
  "SDL_SetHint. `hint` is a keyword from sdl3.consts/hint (:render-vsync,
  :video-allow-screensaver, ...) or the SDL string; booleans become \"1\"/\"0\".
  Answers true when the hint was set."
  [hint value]
  (hints/set-hint (hint-name hint) (hint-value value)))

(defn set-hint-with-priority!
  "SDL_SetHintWithPriority; `priority` is :default, :normal or :override."
  [hint value priority]
  (hints/set-hint-with-priority (hint-name hint) (hint-value value) (enum c/hint-priority priority)))

(defn hint
  "SDL_GetHint: the hint's string value, or nil when unset."
  [hint]
  (hints/get-hint (hint-name hint)))

(defn hint-boolean
  "SDL_GetHintBoolean: the hint as a boolean, `default` when unset."
  [hint default]
  (hints/get-hint-boolean (hint-name hint) (boolean default)))

(defn reset-hint!
  "SDL_ResetHint: back to the default. Answers true when the hint was reset."
  [hint]
  (hints/reset-hint (hint-name hint)))

;; ---------------------------------------------------------------------------
;; memory SDL hands out
;; ---------------------------------------------------------------------------

(defn free!
  "SDL_free: release memory SDL allocated and told you to free."
  [p]
  (when (and p (not (ffi/null? p))) (stdinc/free p))
  nil)

(defn take-string
  "Read the C string at `p` (which SDL allocated and expects you to SDL_free) and
  free it. NULL answers nil."
  [p]
  (when (and p (not (ffi/null? p)))
    (let [s (ffi/ptr->string p)]
      (stdinc/free p)
      s)))

(defn read-strings
  "Read `n` char* entries from the array at `p` (a char** SDL handed out), as a
  vector of strings."
  [p n]
  (mapv (fn [i] (ffi/ptr->string (ffi/read p :pointer (* i (ffi/sizeof :pointer))))) (range n)))

(defn take-ids
  "Read `n` Uint32 ids from the array at `p` (SDL_GetJoysticks and friends) as a
  vector, then SDL_free the array. NULL answers []."
  [p n]
  (if (or (nil? p) (ffi/null? p))
    []
    (let [ids (mapv (fn [i] (ffi/read p :uint32 (* 4 i))) (range n))]
      (stdinc/free p)
      ids)))

(defmacro with-count
  "Call (f ... count-ptr) where SDL answers an array and writes its length through
  the last argument; answer (take-ids array count) — the ids as a vector:

      (with-count raw/get-joysticks)"
  [f & args]
  `(with-outs [n# :int]
     (let [p# (~f ~@args n#)]
       (take-ids p# (ffi/read n# :int)))))

;; ---------------------------------------------------------------------------
;; structs from maps
;; ---------------------------------------------------------------------------

(defn write-fields!
  "Write the entries of map `m` into the struct `layout` at pointer `p`, field by
  field, leaving every field `m` does not name as it is — zero, in memory from
  ffi/alloc. A map value writes a nested struct by path. Answers p.

  SDL's create-info structs are mostly zero-means-default, so this is how the
  wrappers let a caller name only what they care about."
  ([p layout m] (write-fields! p layout [] m))
  ([p layout path m]
   (doseq [[k v] m]
     (let [path' (conj path k)]
       (cond
         (map? v) (write-fields! p layout path' v)
         ;; an array field is written element by element: [:dir 0], [:dir 1] ...
         (sequential? v) (write-fields! p layout path' (zipmap (range) v))
         ;; a :float field refuses 640 and an integer field refuses 3.0; the
         ;; field's current value (zero) has its type, so coerce to that
         (number? v) (let [cur (ffi/read-field p layout path')]
                       (ffi/write-field p layout path'
                                        (cond (double? cur) (double v)
                                              (and (integer? cur) (not (integer? v)))
                                              (if (== v (Math/floor v))
                                                (long v)
                                                (throw (ex-info (str "field " path' " is an integer; got " v) {:path path' :value v})))
                                              :else v)))
         :else (ffi/write-field p layout path' v))))
   p))

(defn alloc-fields
  "Allocate one `layout` in `arena`, zeroed, and write-fields! `m` into it."
  [arena layout m]
  (write-fields! (ffi/alloc arena layout) layout m))

(defn alloc-array
  "Allocate `layout` x (count ms) contiguously in `arena` and write-fields! each
  map of `ms` into its slot; answers [pointer count]. Empty answers [NULL 0]."
  [arena layout ms]
  (let [n (count ms)]
    (if (zero? n)
      [ffi/null 0]
      (let [size (ffi/layout-size layout)
            p (ffi/alloc arena (* n size))]
        (doseq [[i m] (map-indexed vector ms)]
          (write-fields! (ffi/slice p (* i size)) layout m))
        [p n]))))

(defn alloc-pointers
  "A contiguous array of the pointers `ps` in `arena`, for SDL's `T *const *`
  arguments; answers [pointer count]."
  [arena ps]
  (let [n (count ps)]
    (if (zero? n)
      [ffi/null 0]
      (let [w (ffi/sizeof :pointer)
            p (ffi/alloc arena (* n w))]
        (doseq [[i x] (map-indexed vector ps)]
          (ffi/write p :pointer x (* i w)))
        [p n]))))

(defn bytes->ptr
  "Copy the byte-array `bs` into `arena`; answers [pointer length]."
  [arena bs]
  (let [n (alength bs)
        p (ffi/alloc arena (max 1 n))]
    (when (pos? n) (ffi/write-array p bs))
    [p n]))

(defn prop-name
  "A property name: a keyword from sdl3.consts/prop, or the SDL string itself."
  [k]
  (if (keyword? k) (enum c/prop k) k))

(defn array->ptr
  "[pointer byte-length] of `data` copied into `arena`: a byte-, float-, short-,
  int- or long-array (native byte order). A [pointer length] pair passes through."
  [arena data]
  (cond
    (and (vector? data) (= 2 (count data))) data
    (bytes? data) (bytes->ptr arena data)
    :else
    (let [[t w] (cond (instance? (class (float-array 0)) data) [:float 4]
                      (instance? (class (short-array 0)) data) [:int16 2]
                      (instance? (class (int-array 0)) data) [:int32 4]
                      (instance? (class (long-array 0)) data) [:int64 8]
                      (instance? (class (double-array 0)) data) [:double 8]
                      :else (throw (ex-info "expected a byte-, float-, short-, int-, long- or double-array, or [pointer length]"
                                            {:got (type data)})))
          n (* w (alength data))
          p (ffi/alloc arena (max 1 n))]
      (when (pos? n) (ffi/write-array p t data))
      [p n])))
