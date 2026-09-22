(ns sdl3.properties
  "Property groups: SDL_properties.h. A group is an SDL_PropertiesID (an integer);
  SDL hands them out for windows, renderers, textures, streams and devices, and
  takes them to create objects with options the plain constructors lack.

  Names are keywords from sdl3.consts/prop (:window-create-title-string ...) or
  the SDL strings themselves. Values keep their Clojure type (put! and get): a string, a
  boolean, an integer, a double, or a pointer given as {:pointer p}.

      (with-properties [p {:window-create-title-string \"hi\"
                           :window-create-width-number 640
                           :window-create-height-number 480
                           :window-create-resizable-boolean true}]
        (sdl3.video/create-window-with-properties p))"
  (:refer-clojure :exclude [get keys])
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.properties :as props]))

(defsdl global props/get-global-properties
  :doc "The process-wide group SDL keeps; never destroy it.")

(declare put!)

(defn create
  "SDL_CreateProperties: a new empty group, or one holding the entries of map `m`.
  Release it with destroy!."
  ([] (let [id (props/create-properties)]
        (when (zero? id) (throw (core/sdl-error "SDL_CreateProperties")))
        id))
  ([m] (let [id (create)]
         (try
           (doseq [[k v] m] (put! id k v))
           id
           (catch Throwable t (props/destroy-properties id) (throw t))))))

(defsdl destroy! props/destroy-properties)
(defsdl copy! props/copy-properties
  :doc "Copy every entry of group src into group dst (pointer entries with a cleanup callback are skipped).")
(defsdl lock! props/lock-properties)
(defsdl unlock! props/unlock-properties)

(defmacro with-properties
  "Bind `sym` to a group created from map `m` for the body, destroyed on the way out."
  [[sym m] & body]
  `(let [~sym (create ~m)]
     (try ~@body (finally (props/destroy-properties ~sym)))))

(defn put!
  "Set `k` in group `props` from the value's type: a string, boolean, integer,
  float/double, {:pointer p}, or nil to clear it."
  [props k v]
  (let [n (core/prop-name k)]
    (core/check-bool
     "SDL_SetProperty"
     (cond
       (nil? v) (props/clear-property props n)
       (string? v) (props/set-string-property props n v)
       (boolean? v) (props/set-boolean-property props n v)
       (integer? v) (props/set-number-property props n v)
       (number? v) (props/set-float-property props n (double v))
       (and (map? v) (contains? v :pointer)) (props/set-pointer-property props n (or (:pointer v) ffi/null))
       (keyword? v) (props/set-string-property props n (name v))
       :else (throw (ex-info (str "cannot store " (pr-str v) " in a property") {:name n :value v}))))
    nil))

(defn type-of
  "The stored type of `k`: :pointer :string :number :float :boolean, or nil when absent."
  [props k]
  (let [t (core/unenum c/property-type-names (props/get-property-type props (core/prop-name k)))]
    (when-not (= t :invalid) t)))

(defn has? [props k] (props/has-property props (core/prop-name k)))

(defn get
  "The value of `k` read as its stored type (a pointer entry answers the address),
  or `default` (nil) when absent."
  ([props k] (get props k nil))
  ([props k default]
   (let [n (core/prop-name k)]
     (case (type-of props n)
       :string (props/get-string-property props n nil)
       :number (props/get-number-property props n 0)
       :float (props/get-float-property props n 0.0)
       :boolean (props/get-boolean-property props n false)
       :pointer (props/get-pointer-property props n ffi/null)
       default))))

(defn get-pointer [props k] (props/get-pointer-property props (core/prop-name k) ffi/null))
(defn get-string [props k default] (props/get-string-property props (core/prop-name k) default))
(defn get-number [props k default] (props/get-number-property props (core/prop-name k) (long default)))
(defn get-float [props k default] (props/get-float-property props (core/prop-name k) (double default)))
(defn get-boolean [props k default] (props/get-boolean-property props (core/prop-name k) (boolean default)))

(defn clear! [props k]
  (core/check-bool "SDL_ClearProperty" (props/clear-property props (core/prop-name k)))
  nil)

(defn keys
  "SDL_EnumerateProperties: every name in the group, as SDL strings."
  [props]
  (let [acc (atom [])]
    (with-open [a (ffi/confined-arena)]
      (let [cb (ffi/callback a (fn [_ _ name] (swap! acc conj (ffi/ptr->string name)) nil)
                             [:pointer :uint32 :pointer] :void)]
        (core/check-bool "SDL_EnumerateProperties" (props/enumerate-properties props cb ffi/null))))
    @acc))

(def ^:private name->kw
  (into {} (map (fn [[k v]] [v k])) c/prop))

(defn ->map
  "The whole group as a map. Names SDL defines become their sdl3.consts/prop
  keyword (when several keywords share a string, one of them); others stay strings."
  [props]
  (into {} (for [n (keys props)] [(clojure.core/get name->kw n n) (get props n)])))
