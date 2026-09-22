(ns sdl3.io
  "SDL_IOStream: SDL's file and memory streams, SDL_iostream.h. SDL functions
  that load from a stream (sdl3.surface/load-bmp-io, sdl3.audio/load-wav-io,
  sdl3.gamepad/add-mappings-from-io) take one of these.

  A stream is the SDL_IOStream* pointer; close! it (or hand it to a loader with
  closeio true). with-io does both halves:

      (with-io [s (from-file \"save.dat\" \"wb\")]
        (write-bytes! s (.getBytes \"hello\"))
        (write-num! s :u32-le 42))

  Bytes move as byte-arrays. Streams can also be built from Clojure functions
  (open-io), which is how a loader reads from anything jolt can read."
  (:refer-clojure :exclude [load-file])
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.properties :as props]
            [sdl3.raw.iostream :as io]
            [sdl3.raw.stdinc :as stdinc]))

(defn from-file
  "SDL_IOFromFile. `mode` is fopen's: \"rb\", \"wb\", \"ab\", \"r+b\" ..."
  [path mode]
  (core/check-ptr "SDL_IOFromFile" (io/io-from-file (str path) mode)))

(defn dynamic
  "SDL_IOFromDynamicMem: an empty read/write memory stream that grows as written.
  (contents s) copies what it holds out."
  []
  (core/check-ptr "SDL_IOFromDynamicMem" (io/io-from-dynamic-mem)))

(defn from-bytes
  "A read/write memory stream holding a copy of byte-array `bs`, positioned at 0.
  SDL owns the copy, so the array is free to change or go."
  [bs]
  (let [s (dynamic)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)]
        (when (and (pos? n) (not= n (io/write-io s p n)))
          (io/close-io s)
          (throw (core/sdl-error "SDL_WriteIO")))))
    (io/seek-io s 0 (c/io-whence :set))
    s))

(defn from-mem
  "SDL_IOFromMem over `size` bytes at pointer `p`, which must outlive the stream."
  [p size]
  (core/check-ptr "SDL_IOFromMem" (io/io-from-mem p size)))

(defn from-const-mem
  "SDL_IOFromConstMem: read-only from-mem."
  [p size]
  (core/check-ptr "SDL_IOFromConstMem" (io/io-from-const-mem p size)))

;; stream -> the arena holding its open-io callbacks
(def ^:private open-arenas (atom {}))

(defn close!
  "SDL_CloseIO: flush and close the stream; raises when the final flush failed."
  [s]
  (let [ok (io/close-io s)]
    (when-let [a (get @open-arenas s)]
      (swap! open-arenas dissoc s)
      (ffi/close-arena a))
    (core/check-bool "SDL_CloseIO" ok)
    nil))

(defmacro with-io
  "Bind a stream for the body and close it on the way out."
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try ~@body (finally (close! ~sym)))))

(defsdl properties io/get-io-properties)

(defn status
  "SDL_GetIOStatus: :ready :error :eof :not-ready :readonly or :writeonly — why
  the last read or write came up short."
  [s]
  (core/unenum c/io-status-names (io/get-io-status s)))

(defn size
  "SDL_GetIOSize in bytes; raises when the stream cannot tell."
  [s]
  (let [n (io/get-io-size s)]
    (when (neg? n) (throw (core/sdl-error "SDL_GetIOSize")))
    n))

(defn seek!
  "SDL_SeekIO; `whence` is :set (default), :cur or :end. Answers the new position."
  ([s offset] (seek! s offset :set))
  ([s offset whence]
   (let [n (io/seek-io s (long offset) (core/enum c/io-whence whence))]
     (when (neg? n) (throw (core/sdl-error "SDL_SeekIO")))
     n)))

(defn tell [s] (io/tell-io s))

(defn read-bytes
  "SDL_ReadIO up to `n` bytes as a byte-array, shorter at end of stream (check
  status for why). Raises on :error."
  [s n]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (max 1 n))
          got (io/read-io s p n)]
      (when (and (< got n) (= :error (status s)))
        (throw (core/sdl-error "SDL_ReadIO")))
      (ffi/read-array p got))))

(defn read-all
  "Everything from the current position to the end, as a byte-array."
  [s]
  (let [out (java.io.ByteArrayOutputStream.)]
    (loop []
      (let [chunk (read-bytes s 65536)]
        (.write out chunk 0 (alength chunk))
        (if (= 65536 (alength chunk)) (recur) (.toByteArray out))))))

(defn write-bytes!
  "SDL_WriteIO the whole byte-array (or a string's UTF-8); raises when short."
  [s data]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)
            put (if (pos? n) (io/write-io s p n) 0)]
        (when (< put n) (throw (core/sdl-error "SDL_WriteIO")))
        put))))

(defsdl flush! io/flush-io)

(defn contents
  "The bytes a dynamic stream holds, whatever its position."
  [s]
  (let [p (io/get-io-properties s)
        mem (props/get-pointer p :iostream-dynamic-memory-pointer)
        n (size s)]
    (if (or (ffi/null? mem) (zero? n)) (byte-array 0) (ffi/read-array mem n))))

;; ---------------------------------------------------------------------------
;; typed numbers
;; ---------------------------------------------------------------------------

(def ^:private readers
  {:u8 [io/read-u8 :uint8] :s8 [io/read-s8 :int8]
   :u16-le [io/read-u16-le :uint16] :s16-le [io/read-s16-le :int16]
   :u16-be [io/read-u16-be :uint16] :s16-be [io/read-s16-be :int16]
   :u32-le [io/read-u32-le :uint32] :s32-le [io/read-s32-le :int32]
   :u32-be [io/read-u32-be :uint32] :s32-be [io/read-s32-be :int32]
   :u64-le [io/read-u64-le :uint64] :s64-le [io/read-s64-le :int64]
   :u64-be [io/read-u64-be :uint64] :s64-be [io/read-s64-be :int64]})

(def ^:private writers
  {:u8 io/write-u8 :s8 io/write-s8
   :u16-le io/write-u16-le :s16-le io/write-s16-le :u16-be io/write-u16-be :s16-be io/write-s16-be
   :u32-le io/write-u32-le :s32-le io/write-s32-le :u32-be io/write-u32-be :s32-be io/write-s32-be
   :u64-le io/write-u64-le :s64-le io/write-s64-le :u64-be io/write-u64-be :s64-be io/write-s64-be})

(defn read-num
  "Read one integer of type `t` — :u8 :s8, or :u16/:s16/:u32/:s32/:u64/:s64
  with -le or -be — converting from that byte order. Raises at end of stream."
  [s t]
  (let [[f ft] (or (readers t) (throw (ex-info (str "unknown number type " t) {:type t :known (sort (keys readers))})))]
    (with-open [a (ffi/confined-arena)]
      (let [p (ffi/alloc a 8)]
        (core/check-bool (str "SDL_Read " (name t)) (f s p))
        (ffi/read p ft)))))

(defn write-num!
  "Write integer `v` as type `t` (see read-num)."
  [s t v]
  (let [f (or (writers t) (throw (ex-info (str "unknown number type " t) {:type t :known (sort (keys writers))})))]
    (core/check-bool (str "SDL_Write " (name t)) (f s v))
    nil))

;; ---------------------------------------------------------------------------
;; whole files
;; ---------------------------------------------------------------------------

(defn- take-bytes [p n]
  (let [bs (ffi/read-array p n)]
    (stdinc/free p)
    bs))

(defn load-file
  "SDL_LoadFile: a whole file as a byte-array."
  [path]
  (with-outs [n :size_t]
    (take-bytes (core/check-ptr "SDL_LoadFile" (io/load-file (str path) n)) (ffi/read n :size_t))))

(defn load-io
  "SDL_LoadFile_IO: the rest of stream `s` as a byte-array; closes it when `close?`."
  [s close?]
  (with-outs [n :size_t]
    (take-bytes (core/check-ptr "SDL_LoadFile_IO" (io/load-file-io s n (boolean close?))) (ffi/read n :size_t))))

(defn save-file!
  "SDL_SaveFile: write a byte-array (or a string's UTF-8) as the whole file."
  [path data]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)]
        (core/check-bool "SDL_SaveFile" (io/save-file (str path) p n))))
    nil))

;; ---------------------------------------------------------------------------
;; streams from Clojure functions
;; ---------------------------------------------------------------------------

(defn open-io
  "SDL_OpenIO: a stream whose operations are Clojure functions, any may be absent:

    :size   (fn [] n)                    total size, or -1 when unknown
    :seek   (fn [offset whence] pos)     whence :set :cur :end; answer the new position or -1
    :read   (fn [n] byte-array)          up to n bytes; empty at end of stream
    :write  (fn [byte-array] n)          answer how many were taken
    :flush  (fn [] true)
    :close  (fn [] true)                 called once, by close!

  SDL calls them on the thread using the stream. The callbacks live until close!,
  which must be sdl3.io/close! (or with-io) rather than the raw SDL_CloseIO."
  [{:keys [size seek read write flush close]}]
  (let [arena (ffi/shared-arena)
        eof (c/io-status :eof)
        err (c/io-status :error)
        set-status (fn [sp v] (when-not (ffi/null? sp) (ffi/write sp :int v)))
        iface (ffi/alloc arena io/io-stream-interface)]
    (core/write-fields!
     iface io/io-stream-interface
     (cond-> {:version (ffi/layout-size io/io-stream-interface)}
       size (assoc :size (ffi/callback arena (fn [_] (long (size))) [:pointer] :int64))
       seek (assoc :seek (ffi/callback arena (fn [_ off whence] (long (seek off (core/unenum c/io-whence-names whence))))
                             [:pointer :int64 :int] :int64))
       read (assoc :read (ffi/callback arena (fn [_ p n sp]
                               (try
                                 (let [^bytes bs (read n)
                                       got (min n (alength bs))]
                                   (if (zero? got)
                                     (do (set-status sp eof) 0)
                                     (do (ffi/write-array p bs 0 got) got)))
                                 (catch Throwable _ (set-status sp err) 0)))
                             [:pointer :pointer :size_t :pointer] :size_t))
       write (assoc :write (ffi/callback arena (fn [_ p n sp]
                                 (try (long (write (ffi/read-array p n)))
                                      (catch Throwable _ (set-status sp err) 0)))
                               [:pointer :pointer :size_t :pointer] :size_t))
       flush (assoc :flush (ffi/callback arena (fn [_ sp] (try (boolean (flush)) (catch Throwable _ (set-status sp err) false)))
                               [:pointer :pointer] :bool))
       true (assoc :close (ffi/callback arena (fn [_]
                                (let [ok (try (if close (boolean (close)) true) (catch Throwable _ false))]
                                  ok))
                              [:pointer] :bool))))
    (let [s (io/open-io iface ffi/null)]
      (when (ffi/null? s) (ffi/close-arena arena) (throw (core/sdl-error "SDL_OpenIO")))
      ;; close! releases the arena once SDL_CloseIO has returned from :close
      (swap! open-arenas assoc s arena)
      s)))
