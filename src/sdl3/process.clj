(ns sdl3.process
  "Child processes: SDL_process.h.

      (sh \"git\" \"status\" \"--short\")        ;=> {:exit 0 :out \"...\"}

      (let [p (create-process [\"sort\"] {:stdin :app :stdout :app})]
        (sdl3.io/write-bytes! (input p) \"b\\na\\n\")
        (sdl3.io/close! (input p))             ; EOF for the child
        (read-output! p))                      ;=> {:exit 0 :out-bytes #bytes}

  Each of :stdin :stdout :stderr is :inherited (share this process's), :null,
  or :app (a pipe reached through input / output / error-output as sdl3.io
  streams)."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.properties :as props]
            [sdl3.raw.process :as process]
            [sdl3.raw.stdinc :as stdinc]))

(defn- argv
  "A NULL-terminated char* array of `args` in `arena`."
  [arena args]
  (let [w (ffi/sizeof :pointer)
        p (ffi/alloc arena (* w (inc (count args))))]
    (doseq [[i s] (map-indexed vector args)]
      (ffi/write p :pointer (ffi/string->ptr arena (str s)) (* i w)))
    p))

(defn- environment
  "An SDL_Environment holding exactly the entries of map `env`."
  [env]
  (let [e (core/check-ptr "SDL_CreateEnvironment" (stdinc/create-environment false))]
    (doseq [[k v] env]
      (stdinc/set-environment-variable e (name k) (str v) true))
    e))

(defn create-process
  "SDL_CreateProcessWithProperties: start `args` (the program and its arguments).
  Options:

    :stdin :stdout :stderr   :inherited (default for stdin... see below), :null or :app
    :stderr-to-stdout        send stderr into stdout
    :working-directory       a path
    :env                     a map replacing the environment
    :background              detach from the terminal (Windows: no console)

  Without options stdin is :null and stdout/stderr are :inherited, as SDL does."
  ([args] (create-process args {}))
  ([args {:keys [stdin stdout stderr stderr-to-stdout working-directory env background]}]
   (with-open [a (ffi/confined-arena)]
     (let [e (when env (environment env))]
       (try
         (props/with-properties
           [p (cond-> {:process-create-args-pointer {:pointer (argv a args)}}
                stdin (assoc :process-create-stdin-number (core/enum c/process-io stdin))
                stdout (assoc :process-create-stdout-number (core/enum c/process-io stdout))
                stderr (assoc :process-create-stderr-number (core/enum c/process-io stderr))
                stderr-to-stdout (assoc :process-create-stderr-to-stdout-boolean true)
                working-directory (assoc :process-create-working-directory-string (str working-directory))
                e (assoc :process-create-environment-pointer {:pointer e})
                background (assoc :process-create-background-boolean true))]
           (core/check-ptr "SDL_CreateProcessWithProperties" (process/create-process-with-properties p)))
         (finally (when e (stdinc/destroy-environment e))))))))

(defsdl properties process/get-process-properties)
(defn pid [p] (props/get-number (process/get-process-properties p) :process-pid-number 0))

(defsdl input process/get-process-input
  :doc "The child's stdin as an sdl3.io stream (with :stdin :app). Close it to send EOF.")
(defsdl output process/get-process-output
  :doc "The child's stdout as an sdl3.io stream (with :stdout :app).")

(defn error-output
  "The child's stderr as an sdl3.io stream (with :stderr :app)."
  [p]
  (core/check-ptr "SDL_GetProcessProperties stderr"
                  (props/get-pointer (process/get-process-properties p) :process-stderr-pointer)))

(defn read-output!
  "SDL_ReadProcess: read the child's stdout until it exits, and answer
  {:exit code :out-bytes byte-array}. Needs :stdout :app."
  [p]
  (with-outs [n :size_t code :int]
    (let [buf (core/check-ptr "SDL_ReadProcess" (process/read-process p n code))
          bs (ffi/read-array buf (ffi/read n :size_t))]
      (stdinc/free buf)
      {:exit (ffi/read code :int) :out-bytes bs})))

(defn wait!
  "SDL_WaitProcess: the exit code, blocking until the child exits when `block?`
  (default true); nil when it is still running and block? is false."
  ([p] (wait! p true))
  ([p block?]
   (with-outs [code :int]
     (when (process/wait-process p (boolean block?) code)
       (ffi/read code :int)))))

(defn kill!
  "SDL_KillProcess: stop the child, politely (SIGTERM) unless `force?` (SIGKILL)."
  ([p] (kill! p false))
  ([p force?] (core/check-bool "SDL_KillProcess" (process/kill-process p (boolean force?))) nil))

(defsdl destroy! process/destroy-process
  :doc "Release the process handle; the child keeps running unless killed first.")

(defn sh
  "Run a program to completion and answer {:exit code :out string}, with stderr
  passed through. The last argument may be an options map for create-process."
  [& args]
  (let [[args opts] (if (map? (last args)) [(butlast args) (last args)] [args {}])
        p (create-process args (merge {:stdout :app} opts))]
    (try
      (let [{:keys [exit out-bytes]} (read-output! p)]
        {:exit exit :out (String. ^bytes out-bytes "UTF-8")})
      (finally (process/destroy-process p)))))
