(ns sdl3.thread
  "SDL threads, locks and atomics: SDL_thread.h, SDL_mutex.h, SDL_atomic.h.

  Jolt has its own threads (future, Thread) and locks, and jolt code should
  normally use those. These are for sharing with C: a mutex an audio callback
  also takes, a semaphore C code signals, an atomic counter in native memory
  both sides read, or a thread SDL itself must own.

  Locks and waits are :blocking foreign calls, so the collector runs while a
  thread is parked in one.

      (let [t (create-thread \"worker\" (fn [] (reduce + (range 1e6))))]
        (wait-thread! t))                      ;=> 499999500000

      (let [m (create-mutex)]
        (with-lock [m] (swap! shared inc))
        (destroy-mutex! m))"
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.thread :as thread]
            [sdl3.raw.mutex :as mutex]
            [sdl3.raw.atomic :as atomic]))

;; ---------------------------------------------------------------------------
;; threads
;; ---------------------------------------------------------------------------

;; thread -> {:arena :result} while joinable. A detached thread's arena stays
;; here for the life of the process: SDL frees a detached thread's handle when it
;; finishes, so there is no moment jolt can safely know its callback is done.
(def ^:private threads (atom {}))

(defn create-thread
  "SDL_CreateThread: run (f) on a new thread SDL starts, named `name`. Answers the
  thread; wait-thread! returns f's value (or rethrows what it threw), and
  detach-thread! lets it run unobserved."
  [name f]
  (let [arena (ffi/auto-arena)
        result (promise)
        cb (ffi/callback arena
                         (fn [_]
                           (try (let [v (f)] (deliver result [:ok v]) (if (integer? v) (unchecked-int v) 0))
                                (catch Throwable t (deliver result [:error t]) -1)))
                         [:pointer] :int :collect-safe)
        ;; SDL_CreateThread is a header macro over this; NULL begin/end functions
        ;; make SDL use the platform's own thread start on every OS
        t (thread/create-thread-runtime cb (str name) ffi/null ffi/null ffi/null)]
    (when (ffi/null? t) (throw (core/sdl-error "SDL_CreateThread")))
    (swap! threads assoc t {:arena arena :result result})
    t))

(defn wait-thread!
  "SDL_WaitThread: block until the thread ends; answer its function's value, or
  rethrow what it threw. The thread is gone afterwards."
  [t]
  (with-outs [status :int]
    (thread/wait-thread t status))
  (let [{:keys [result]} (get @threads t)]
    (swap! threads dissoc t)
    (when result
      (let [[kind v] @result]
        (if (= kind :error) (throw v) v)))))

(defn detach-thread!
  "SDL_DetachThread: let the thread run to completion unobserved."
  [t]
  (swap! threads update t dissoc :result)
  (thread/detach-thread t)
  nil)

(defsdl thread-name thread/get-thread-name :nullable true)
(defsdl thread-id thread/get-thread-id)
(defsdl current-thread-id thread/get-current-thread-id)

(defn thread-state
  "SDL_GetThreadState: :alive, :complete, :detached or :unknown."
  [t]
  (core/unenum c/thread-state-names (thread/get-thread-state t)))

(defn set-current-thread-priority!
  "SDL_SetCurrentThreadPriority: :low, :normal, :high or :time-critical."
  [priority]
  (core/check-bool "SDL_SetCurrentThreadPriority" (thread/set-current-thread-priority (core/enum c/thread-priority priority)))
  nil)

;; ---------------------------------------------------------------------------
;; mutexes, read-write locks, semaphores, conditions
;; ---------------------------------------------------------------------------

(defsdl create-mutex mutex/create-mutex)
(defsdl lock-mutex! mutex/lock-mutex)
(defsdl try-lock-mutex! mutex/try-lock-mutex :pred true
  :doc "Take the mutex if it is free; answers whether it was taken.")
(defsdl unlock-mutex! mutex/unlock-mutex)
(defsdl destroy-mutex! mutex/destroy-mutex)

(defmacro with-lock
  "Hold mutex `m` for the body."
  [[m] & body]
  `(let [m# ~m]
     (mutex/lock-mutex m#)
     (try ~@body (finally (mutex/unlock-mutex m#)))))

(defsdl create-rw-lock mutex/create-rw-lock)
(defsdl lock-for-reading! mutex/lock-rw-lock-for-reading)
(defsdl lock-for-writing! mutex/lock-rw-lock-for-writing)
(defsdl try-lock-for-reading! mutex/try-lock-rw-lock-for-reading :pred true)
(defsdl try-lock-for-writing! mutex/try-lock-rw-lock-for-writing :pred true)
(defsdl unlock-rw-lock! mutex/unlock-rw-lock)
(defsdl destroy-rw-lock! mutex/destroy-rw-lock)

(defmacro with-read-lock
  "Hold read-write lock `l` shared for the body."
  [[l] & body]
  `(let [l# ~l]
     (mutex/lock-rw-lock-for-reading l#)
     (try ~@body (finally (mutex/unlock-rw-lock l#)))))

(defmacro with-write-lock
  "Hold read-write lock `l` exclusively for the body."
  [[l] & body]
  `(let [l# ~l]
     (mutex/lock-rw-lock-for-writing l#)
     (try ~@body (finally (mutex/unlock-rw-lock l#)))))

(defn create-semaphore
  "SDL_CreateSemaphore with `initial` permits (default 0)."
  ([] (create-semaphore 0))
  ([initial] (core/check-ptr "SDL_CreateSemaphore" (mutex/create-semaphore (int initial)))))

(defsdl destroy-semaphore! mutex/destroy-semaphore)
(defsdl wait-semaphore! mutex/wait-semaphore
  :doc "Take a permit, blocking until one is available.")
(defsdl try-wait-semaphore! mutex/try-wait-semaphore :pred true)
(defsdl signal-semaphore! mutex/signal-semaphore
  :doc "Release a permit.")
(defsdl semaphore-value mutex/get-semaphore-value)

(defn wait-semaphore-timeout!
  "Take a permit within `ms`; answers whether one was taken."
  [sem ms]
  (mutex/wait-semaphore-timeout sem (int ms)))

(defsdl create-condition mutex/create-condition)
(defsdl destroy-condition! mutex/destroy-condition)
(defsdl signal-condition! mutex/signal-condition)
(defsdl broadcast-condition! mutex/broadcast-condition)
(defsdl wait-condition! mutex/wait-condition
  :doc "Release mutex, wait for a signal, and retake the mutex. Hold mutex when calling.")

(defn wait-condition-timeout!
  "As wait-condition!, giving up after `ms`; answers whether it was signaled."
  [cnd m ms]
  (mutex/wait-condition-timeout cnd m (int ms)))

;; ---------------------------------------------------------------------------
;; atomics in native memory
;; ---------------------------------------------------------------------------

(defn atomic-int
  "A caller-owned SDL_AtomicInt holding `v` (default 0); release it with free!.
  Its address can be handed to C."
  ([] (atomic-int 0))
  ([v] (let [p (ffi/alloc (ffi/layout-size atomic/atomic-int))] (atomic/set-atomic-int p (int v)) p)))

(defn atomic-u32
  "A caller-owned SDL_AtomicU32 holding `v`; release it with free!."
  ([] (atomic-u32 0))
  ([v] (let [p (ffi/alloc (ffi/layout-size atomic/atomic-u32))] (atomic/set-atomic-u32 p v) p)))

(defn atomic-pointer
  "A caller-owned pointer cell holding `v` (default NULL); release it with free!."
  ([] (atomic-pointer ffi/null))
  ([v] (let [p (ffi/alloc :pointer)] (atomic/set-atomic-pointer p v) p)))

(defn free! "Release an atomic from atomic-int, atomic-u32 or atomic-pointer." [a] (ffi/free a) nil)

(defn get-int [a] (atomic/get-atomic-int a))
(defn set-int! "Store v; answers the previous value." [a v] (atomic/set-atomic-int a (int v)))
(defn add-int! "Add v; answers the previous value." [a v] (atomic/add-atomic-int a (int v)))
(defn cas-int! "Compare-and-swap; answers whether it swapped." [a old new] (atomic/compare-and-swap-atomic-int a (int old) (int new)))

(defn get-u32 [a] (atomic/get-atomic-u32 a))
(defn set-u32! [a v] (atomic/set-atomic-u32 a v))
(defn add-u32! [a v] (atomic/add-atomic-u32 a (int v)))
(defn cas-u32! [a old new] (atomic/compare-and-swap-atomic-u32 a old new))

(defn get-pointer [a] (atomic/get-atomic-pointer a))
(defn set-pointer! [a v] (atomic/set-atomic-pointer a v))
(defn cas-pointer! [a old new] (atomic/compare-and-swap-atomic-pointer a old new))

(defn spinlock
  "A caller-owned SDL_SpinLock, unlocked; release it with free!."
  []
  (ffi/alloc :int))

(defsdl lock-spinlock! atomic/lock-spinlock)
(defsdl try-lock-spinlock! atomic/try-lock-spinlock :pred true)
(defsdl unlock-spinlock! atomic/unlock-spinlock)

(defmacro with-spinlock
  "Hold spinlock `l` for the (short!) body."
  [[l] & body]
  `(let [l# ~l]
     (atomic/lock-spinlock l#)
     (try ~@body (finally (atomic/unlock-spinlock l#)))))

(defsdl memory-barrier-release! atomic/memory-barrier-release-function)
(defsdl memory-barrier-acquire! atomic/memory-barrier-acquire-function)
