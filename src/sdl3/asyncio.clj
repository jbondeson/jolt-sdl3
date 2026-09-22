(ns sdl3.asyncio
  "Asynchronous file IO: SDL_asyncio.h. Reads and writes are queued, run on SDL's
  IO threads, and their results collected from a queue — the way to stream
  assets without stalling a frame.

      (let [q (create-queue)]
        (load-file! \"level1.bin\" q :level1)
        (load-file! \"music.ogg\" q :music)
        ;; each frame:
        (when-let [{:keys [tag result data]} (poll-result q)]
          (when (= result :complete) (install! tag data))))

  Every request takes a `tag`, any Clojure value, handed back in its result.
  Results are maps:

    {:tag :level1 :type :read|:write|:close :result :complete|:failure|:canceled
     :data byte-array (reads) :offset n :bytes-requested n :bytes-transferred n
     :asyncio the-file-or-nil}

  The buffers behind requests are owned here and released once their result is
  collected, so collect every result (or destroy-queue!, which waits for them)."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.asyncio :as aio]
            [sdl3.raw.stdinc :as stdinc]))

;; request id (passed to SDL as userdata) -> {:tag :buffer :owned? :sdl-owned?}
(def ^:private requests (atom {}))
(def ^:private next-id (atom 0))

(defn- register! [info]
  (let [id (swap! next-id inc)]
    (swap! requests assoc id info)
    id))

(defn create-queue "SDL_CreateAsyncIOQueue." [] (core/check-ptr "SDL_CreateAsyncIOQueue" (aio/create-async-io-queue)))

(defn open
  "SDL_AsyncIOFromFile: open `path` with `mode` (\"r\", \"w\", \"r+\", \"w+\"; always binary)."
  [path mode]
  (core/check-ptr "SDL_AsyncIOFromFile" (aio/async-io-from-file (str path) mode)))

(defn size [f]
  (let [n (aio/get-async-io-size f)]
    (when (neg? n) (throw (core/sdl-error "SDL_GetAsyncIOSize")))
    n))

(defn read!
  "SDL_ReadAsyncIO: queue a read of `n` bytes at `offset`; the result's :data holds them."
  [f offset n queue tag]
  (let [buf (ffi/alloc (max 1 n))
        id (register! {:tag tag :buffer buf :owned? true})]
    (when-not (aio/read-async-io f buf (long offset) (long n) queue id)
      (swap! requests dissoc id)
      (ffi/free buf)
      (throw (core/sdl-error "SDL_ReadAsyncIO")))
    id))

(defn write!
  "SDL_WriteAsyncIO: queue a write of `data` (a byte-array or a string's UTF-8) at `offset`."
  [f offset data queue tag]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)
        n (alength ^bytes bs)
        buf (ffi/alloc (max 1 n))]
    (when (pos? n) (ffi/write-array buf bs))
    (let [id (register! {:tag tag :buffer buf :owned? true})]
      (when-not (aio/write-async-io f buf (long offset) (long n) queue id)
        (swap! requests dissoc id)
        (ffi/free buf)
        (throw (core/sdl-error "SDL_WriteAsyncIO")))
      id)))

(defn close!
  "SDL_CloseAsyncIO: queue closing `f` (flushing first when `flush?`); the file is
  gone once its :close result arrives. Every file must be closed this way."
  [f flush? queue tag]
  (let [id (register! {:tag tag})]
    (when-not (aio/close-async-io f (boolean flush?) queue id)
      (swap! requests dissoc id)
      (throw (core/sdl-error "SDL_CloseAsyncIO")))
    id))

(defn load-file!
  "SDL_LoadFileAsync: queue reading a whole file; the result's :data holds it."
  [path queue tag]
  (let [id (register! {:tag tag :sdl-owned? true})]
    (when-not (aio/load-file-async (str path) queue id)
      (swap! requests dissoc id)
      (throw (core/sdl-error "SDL_LoadFileAsync")))
    id))

(defn- decode [outcome]
  (let [{:keys [asyncio type result buffer offset bytes-requested bytes-transferred userdata]}
        (ffi/read outcome aio/async-io-outcome)
        {:keys [tag owned? sdl-owned?]} (get @requests userdata)
        kind (core/unenum c/async-io-task-type-names type)]
    (swap! requests dissoc userdata)
    (let [data (when (and (= kind :read) (not (ffi/null? buffer)))
                 (ffi/read-array buffer bytes-transferred))]
      (when (and owned? (not (ffi/null? buffer))) (ffi/free buffer))
      (when (and sdl-owned? (not (ffi/null? buffer))) (stdinc/free buffer))
      (cond-> {:tag tag :type kind :result (core/unenum c/async-io-result-names result)
               :offset offset :bytes-requested bytes-requested :bytes-transferred bytes-transferred
               :asyncio (core/nullable asyncio)}
        data (assoc :data data)))))

(defn poll-result
  "SDL_GetAsyncIOResult: the next finished request as a map, or nil when none has finished."
  [queue]
  (with-open [a (ffi/confined-arena)]
    (let [o (ffi/alloc a aio/async-io-outcome)]
      (when (aio/get-async-io-result queue o) (decode o)))))

;; queue -> how many times signal-queue! was called on it
(def ^:private signals (atom {}))

(defn wait-result
  "SDL_WaitAsyncIOResult: the next finished request, waiting up to `ms` (-1
  forever); nil on timeout or after signal-queue!.

  SDL may return early without a result when the OS wakes more than one waiting
  thread; this keeps waiting out the rest of the timeout in that case."
  ([queue] (wait-result queue -1))
  ([queue ms]
   (let [signaled (get @signals queue 0)
         deadline (when-not (neg? ms) (+ (System/currentTimeMillis) ms))]
     (with-open [a (ffi/confined-arena)]
       (let [o (ffi/alloc a aio/async-io-outcome)]
         (loop []
           (let [remaining (if deadline (max 0 (- deadline (System/currentTimeMillis))) -1)]
             (cond
               (aio/wait-async-io-result queue o (int remaining)) (decode o)
               (not= signaled (get @signals queue 0)) nil
               (and deadline (>= (System/currentTimeMillis) deadline)) nil
               :else (recur)))))))))

(defn signal-queue!
  "SDL_SignalAsyncIOQueue: wake every thread in wait-result on `queue`; those
  without a result answer nil."
  [queue]
  (swap! signals update queue (fnil inc 0))
  (aio/signal-async-io-queue queue)
  nil)

(defn destroy-queue!
  "SDL_DestroyAsyncIOQueue: wait for the queue's outstanding requests, discard
  their results, and free the queue."
  [queue]
  (loop [] (when (poll-result queue) (recur)))
  (aio/destroy-async-io-queue queue)
  (swap! signals dissoc queue)
  nil)
