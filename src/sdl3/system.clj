(ns sdl3.system
  "What the machine is and does: CPU features (SDL_cpuinfo.h), preferred locales
  (SDL_locale.h), battery (SDL_power.h), opening URLs (SDL_misc.h), loading
  shared objects (SDL_loadso.h), and the platform queries of SDL_system.h.

  The platform-specific hooks in SDL_system.h (Windows message hooks, X11 event
  hooks, iOS animation callbacks, Android JNI, GDK) are in sdl3.raw.system."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.cpuinfo :as cpu]
            [sdl3.raw.locale :as locale]
            [sdl3.raw.power :as power]
            [sdl3.raw.misc :as misc]
            [sdl3.raw.loadso :as loadso]
            [sdl3.raw.system :as sys]))

;; ---------------------------------------------------------------------------
;; CPU and memory
;; ---------------------------------------------------------------------------

(defsdl logical-cpu-cores cpu/get-num-logical-cpu-cores)
(defsdl cpu-cache-line-size cpu/get-cpu-cache-line-size)
(defsdl system-ram cpu/get-system-ram :doc "Installed RAM in MiB.")
(defsdl system-page-size cpu/get-system-page-size)
(defsdl simd-alignment cpu/get-simd-alignment)

(def ^:private feature-fns
  {:altivec cpu/has-alti-vec :mmx cpu/has-mmx :sse cpu/has-sse :sse2 cpu/has-sse2 :sse3 cpu/has-sse3
   :sse41 cpu/has-sse41 :sse42 cpu/has-sse42 :avx cpu/has-avx :avx2 cpu/has-avx2 :avx512f cpu/has-avx512f
   :armsimd cpu/has-armsimd :neon cpu/has-neon :lsx cpu/has-lsx :lasx cpu/has-lasx})

(defn cpu-features
  "The set of SIMD features this CPU has, from :altivec :mmx :sse :sse2 :sse3
  :sse41 :sse42 :avx :avx2 :avx512f :armsimd :neon :lsx :lasx."
  []
  (into #{} (for [[k f] feature-fns :when (f)] k)))

(defn cpu-info
  "Everything above as one map."
  []
  {:logical-cores (logical-cpu-cores) :cache-line-size (cpu-cache-line-size)
   :ram-mib (system-ram) :page-size (system-page-size) :simd-alignment (simd-alignment)
   :features (cpu-features)})

;; ---------------------------------------------------------------------------
;; locale, power, URLs
;; ---------------------------------------------------------------------------

(defn preferred-locales
  "SDL_GetPreferredLocales: the user's languages, most preferred first, as
  [{:language \"en\" :country \"US\"} ...] (:country may be nil)."
  []
  (with-outs [n :int]
    (let [p (locale/get-preferred-locales n)]
      (if (ffi/null? p)
        []
        (let [ls (mapv (fn [i]
                         (let [{:keys [language country]} (ffi/read (ffi/read p :pointer (* i (ffi/sizeof :pointer))) locale/locale)]
                           {:language (ffi/ptr->string language)
                            :country (when-not (ffi/null? country) (ffi/ptr->string country))}))
                       (range (ffi/read n :int)))]
          (core/free! p)
          ls)))))

(defn power-info
  "SDL_GetPowerInfo: {:state :on-battery|:no-battery|:charging|:charged|:unknown
  :seconds remaining-or-nil :percent 0-100-or-nil}."
  []
  (with-outs [secs :int pct :int]
    (let [st (core/unenum c/power-state-names (power/get-power-info secs pct))
          s (ffi/read secs :int) p (ffi/read pct :int)]
      (when (= st :error) (throw (core/sdl-error "SDL_GetPowerInfo")))
      {:state st :seconds (when-not (neg? s) s) :percent (when-not (neg? p) p)})))

(defsdl open-url! misc/open-url
  :doc "Open a URL (or file:// path) in the user's browser or the handler for it.")

;; ---------------------------------------------------------------------------
;; shared objects
;; ---------------------------------------------------------------------------

(defsdl load-object loadso/load-object
  :doc "SDL_LoadObject: dlopen a shared library; unload-object! it when done.")
(defsdl load-function loadso/load-function
  :doc "The address of symbol name in a loaded object; raises when absent. Call it through jolt.ffi.")
(defsdl unload-object! loadso/unload-object)

;; ---------------------------------------------------------------------------
;; platform
;; ---------------------------------------------------------------------------

(defsdl tablet? sys/is-tablet :pred true)
(defsdl tv? sys/is-tv :pred true)

(defn sandbox
  "SDL_GetSandbox: :none, :unknown-container, :flatpak, :snap or :macos (App Sandbox)."
  []
  (core/unenum c/sandbox-names (sys/get-sandbox)))
