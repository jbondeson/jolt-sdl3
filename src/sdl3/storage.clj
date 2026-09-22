(ns sdl3.storage
  "Abstract storage: SDL_storage.h. The portable way to reach game data and saves
  on platforms without a plain filesystem (consoles, sandboxed stores); on a
  desktop each kind maps to a directory.

    open-title   read-only data shipped with the game
    open-user    the user's writable save area for org/app
    open-file    any directory, for tools and tests

  A container may not be usable the moment it opens; ready? says when, and
  wait-ready! blocks until then. Paths inside use \"/\" on every platform.

      (with-storage [st (open-user \"my-org\" \"my-game\")]
        (wait-ready! st)
        (write-file! st \"save1.edn\" (pr-str state)))"
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.storage :as storage]
            [sdl3.raw.filesystem :as fs]
            [sdl3.raw.timer :as timer]))

(defn open-title
  "SDL_OpenTitleStorage: the game's read-only data (`override` a path to use
  instead, or nil)."
  ([] (open-title nil 0))
  ([override props] (core/check-ptr "SDL_OpenTitleStorage" (storage/open-title-storage override (or props 0)))))

(defn open-user
  "SDL_OpenUserStorage: the user's save area for `org`/`app`."
  ([org app] (open-user org app 0))
  ([org app props] (core/check-ptr "SDL_OpenUserStorage" (storage/open-user-storage org app (or props 0)))))

(defn open-file
  "SDL_OpenFileStorage: a container over directory `path` (nil: no root, paths are absolute)."
  [path]
  (core/check-ptr "SDL_OpenFileStorage" (storage/open-file-storage (some-> path str))))

(defsdl close! storage/close-storage)
(defsdl ready? storage/storage-ready :pred true)

(defmacro with-storage
  "Bind a container for the body and close it on the way out."
  [[sym expr] & body]
  `(let [~sym ~expr]
     (try ~@body (finally (storage/close-storage ~sym)))))

(defn wait-ready!
  "Block until the container is ready?, up to `timeout-ms` (default 10s); raises on timeout."
  ([st] (wait-ready! st 10000))
  ([st timeout-ms]
   (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
     (loop []
       (cond
         (storage/storage-ready st) nil
         (> (System/currentTimeMillis) deadline) (throw (ex-info "storage not ready in time" {:timeout-ms timeout-ms}))
         :else (do (timer/delay 1) (recur)))))))

(defn file-size
  "SDL_GetStorageFileSize in bytes."
  [st path]
  (with-outs [n :uint64]
    (core/check-bool "SDL_GetStorageFileSize" (storage/get-storage-file-size st path n))
    (ffi/read n :uint64)))

(defn read-file
  "The whole file at `path` as a byte-array."
  [st path]
  (let [n (file-size st path)]
    (with-open [a (ffi/confined-arena)]
      (let [p (ffi/alloc a (max 1 n))]
        (core/check-bool "SDL_ReadStorageFile" (storage/read-storage-file st path p n))
        (ffi/read-array p n)))))

(defn read-string-file
  "The file at `path` decoded as UTF-8."
  [st path]
  (String. ^bytes (read-file st path) "UTF-8"))

(defn write-file!
  "SDL_WriteStorageFile: `data` (a byte-array or a string's UTF-8) as the whole file."
  [st path data]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)]
        (core/check-bool "SDL_WriteStorageFile" (storage/write-storage-file st path p n)))))
  nil)

(defsdl mkdir! storage/create-storage-directory)
(defsdl remove! storage/remove-storage-path
  :doc "Remove a file or an empty directory.")
(defsdl rename! storage/rename-storage-path)
(defsdl copy! storage/copy-storage-file)
(defsdl space-remaining storage/get-storage-space-remaining)

(defn path-info
  "SDL_GetStoragePathInfo: {:type :file|:directory|:other :size :create-time
  :modify-time :access-time} (times in SDL_Time nanoseconds), or nil when
  nothing is at `path`."
  [st path]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a fs/path-info)]
      (when (storage/get-storage-path-info st path p)
        (update (ffi/read p fs/path-info) :type #(core/unenum c/path-type-names %))))))

(defn exists? [st path] (some? (path-info st path)))

(defn list-dir
  "The names directly inside directory `path` (\"\" for the root; SDL refuses \".\")."
  [st path]
  (let [acc (atom [])
        continue (c/enumeration-result :continue)]
    (with-open [a (ffi/confined-arena)]
      (let [cb (ffi/callback a (fn [_ _ fname] (swap! acc conj (ffi/ptr->string fname)) continue)
                             [:pointer :pointer :pointer] :int)]
        (core/check-bool "SDL_EnumerateStorageDirectory" (storage/enumerate-storage-directory st path cb ffi/null))))
    @acc))

(defn glob
  "SDL_GlobStorageDirectory: every path under `path`, recursively, whose path
  relative to it matches `pattern` (nil for all). * and ? do not cross \"/\", so
  \"*.edn\" matches top-level files only and \"*/*.edn\" one level down. `flags`
  may be :caseinsensitive."
  ([st path pattern] (glob st path pattern nil))
  ([st path pattern flags]
   (with-outs [n :int]
     (let [p (core/check-ptr "SDL_GlobStorageDirectory"
                             (storage/glob-storage-directory st path pattern (core/flags c/glob-flags flags) n))
           ss (core/read-strings p (ffi/read n :int))]
       (core/free! p)
       ss))))
