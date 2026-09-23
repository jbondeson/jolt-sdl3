(ns sdl3.net
  "Portable TCP and UDP: SDL_net (SDL3_net). Needs libSDL3_net installed
  (`brew install sdl3_net`); it is an optional native, so check available?
  before relying on it. (Jolt has its own sockets too; SDL_net is here for code
  that wants SDL's cross-platform, non-blocking model.)

  Everything is non-blocking unless a function says it waits:

      (net/with-net
        (let [addr (net/resolve! \"example.com\")          ; waits for DNS
              sock (net/connect addr 80)]
          (net/wait-connected sock 5000)                  ;=> :success
          (net/write! sock \"GET / HTTP/1.0\\r\\n\\r\\n\")
          (net/read sock 4096)                             ; whatever has arrived
          (net/destroy-socket! sock)
          (net/unref-address! addr)))

  Addresses are reference-counted: every address this namespace hands you (from
  resolve, resolve!, local-addresses or a datagram) holds a reference that
  unref-address! releases. Statuses are :success, :waiting or :failure."
  (:refer-clojure :exclude [read])
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts.net :as c]
            [sdl3.raw.net :as net]))

(def ^:private availability
  (delay (try (net/version) true (catch Throwable _ false))))

(defn available?
  "Is libSDL3_net installed and loaded? Every other function throws when it isn't."
  []
  @availability)

(defn version
  "The linked SDL_net's version: {:major :minor :micro :string}."
  []
  (let [v (net/version) major (quot v 1000000) minor (mod (quot v 1000) 1000) micro (mod v 1000)]
    {:major major :minor minor :micro micro :string (str major "." minor "." micro)}))

(defn init!
  "NET_Init. Pair with quit!; calls nest."
  []
  (core/check-bool "NET_Init" (net/init))
  nil)

(defsdl quit! net/quit)

(defmacro with-net
  "Run body between init! and quit!."
  [& body]
  `(do (init!) (try ~@body (finally (quit!)))))

(defn- status [v] (core/unenum c/status-names v))

;; ---------------------------------------------------------------------------
;; addresses
;; ---------------------------------------------------------------------------

(defn resolve
  "NET_ResolveHostname: start resolving `host` (a name or a numeric address) in
  the background; answers the address at once. Poll address-status or
  wait-resolved before using it."
  [host]
  (core/check-ptr "NET_ResolveHostname" (net/resolve-hostname (str host))))

(defn wait-resolved
  "NET_WaitUntilResolved: wait up to `ms` (-1 forever, 0 poll) for resolution to
  finish; answers its status."
  [addr ms]
  (status (net/wait-until-resolved addr (int ms))))

(defn resolve!
  "Resolve `host` and wait up to `ms` (default forever) for it; answers the address,
  raising when resolution fails or times out."
  ([host] (resolve! host -1))
  ([host ms]
   (let [addr (resolve host)
         st (wait-resolved addr ms)]
     (if (= :success st)
       addr
       (let [e (core/sdl-error (str "NET_ResolveHostname " host))]
         (net/unref-address addr)
         (throw (if (= :waiting st) (ex-info (str "resolving " host " timed out") {:host host}) e)))))))

(defn address-status [addr] (status (net/get-address-status addr)))
(defsdl address-string net/get-address-string :nullable true
  :doc "The address in human-readable form, e.g. \"93.184.215.14\"; nil until resolved.")

(defn address-bytes
  "The resolved address's bytes as SDL_net stores them, as a byte-array. These are
  the platform's own socket address structure, whose layout differs by OS, so use
  address-string for anything portable."
  [addr]
  (with-outs [n :int]
    (let [p (core/check-ptr "NET_GetAddressBytes" (net/get-address-bytes addr n))]
      (ffi/read-array p (ffi/read n :int)))))

(defsdl ref-address! net/ref-address
  :doc "Take another reference to an address; answers it.")
(defsdl unref-address! net/unref-address)

(defn compare-addresses
  "NET_CompareAddresses: negative, zero or positive, like compare."
  [a b]
  (net/compare-addresses a b))

(defn local-addresses
  "NET_GetLocalAddresses: this machine's addresses, as strings."
  []
  (with-outs [n :int]
    (let [p (net/get-local-addresses n)]
      (if (ffi/null? p)
        []
        (let [addrs (mapv #(net/get-address-string (ffi/read p :pointer (* % (ffi/sizeof :pointer))))
                          (range (ffi/read n :int)))]
          (net/free-local-addresses p)
          addrs)))))

;; ---------------------------------------------------------------------------
;; TCP
;; ---------------------------------------------------------------------------

(defn connect
  "NET_CreateClient: start connecting to `addr` (resolved) on `port`; answers the
  stream socket at once. wait-connected or connection-status tells you when."
  [addr port]
  (core/check-ptr "NET_CreateClient" (net/create-client addr (int port) 0)))

(defn wait-connected
  "NET_WaitUntilConnected: wait up to `ms` (-1 forever, 0 poll); answers the status."
  [sock ms]
  (status (net/wait-until-connected sock (int ms))))

(defn connection-status [sock] (status (net/get-connection-status sock)))
(defsdl socket-address net/get-stream-socket-address
  :doc "The address at the other end; unref-address! it when done.")

(defn listen
  "NET_CreateServer: accept TCP connections on `port`, on local address `addr`
  (nil: every interface)."
  ([port] (listen nil port))
  ([addr port] (core/check-ptr "NET_CreateServer" (net/create-server (or addr ffi/null) (int port) 0))))

(defn accept
  "NET_AcceptClient: the next pending connection's stream socket, or nil when none
  is waiting (it never blocks)."
  [server]
  (with-outs [pp :pointer]
    (core/check-bool "NET_AcceptClient" (net/accept-client server pp))
    (core/nullable (ffi/read pp :pointer))))

(defsdl close-server! net/destroy-server)

(defn write!
  "NET_WriteToStreamSocket: queue `data` (a byte-array or a string's UTF-8) to send.
  It returns at once; pending-writes and wait-drained follow its progress."
  [sock data]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)]
        (core/check-bool "NET_WriteToStreamSocket" (net/write-to-stream-socket sock p (int n))))))
  nil)

(defn pending-writes
  "Bytes queued but not yet sent."
  [sock]
  (let [n (net/get-stream-socket-pending-writes sock)]
    (when (neg? n) (throw (core/sdl-error "NET_GetStreamSocketPendingWrites")))
    n))

(defn wait-drained
  "NET_WaitUntilStreamSocketDrained: wait up to `ms` for queued writes to go out;
  answers the bytes still pending."
  [sock ms]
  (let [n (net/wait-until-stream-socket-drained sock (int ms))]
    (when (neg? n) (throw (core/sdl-error "NET_WaitUntilStreamSocketDrained")))
    n))

(defn read
  "NET_ReadFromStreamSocket: up to `n` bytes that have arrived, as a byte-array —
  empty when none has (it never blocks). Raises when the connection has failed or
  closed."
  [sock n]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (max 1 n))
          got (net/read-from-stream-socket sock p (int n))]
      (when (neg? got) (throw (core/sdl-error "NET_ReadFromStreamSocket")))
      (ffi/read-array p got))))

(defsdl destroy-socket! net/destroy-stream-socket)

;; ---------------------------------------------------------------------------
;; UDP
;; ---------------------------------------------------------------------------

(defn datagram-socket
  "NET_CreateDatagramSocket: a UDP socket bound to `port` (0: any free port) on
  local address `addr` (nil: every interface)."
  ([port] (datagram-socket nil port))
  ([addr port] (core/check-ptr "NET_CreateDatagramSocket" (net/create-datagram-socket (or addr ffi/null) (int port) 0))))

(defn send!
  "NET_SendDatagram: send `data` (a byte-array or a string's UTF-8) to `addr`:`port`."
  [sock addr port data]
  (let [bs (if (string? data) (.getBytes ^String data "UTF-8") data)]
    (with-open [a (ffi/confined-arena)]
      (let [[p n] (core/bytes->ptr a bs)]
        (core/check-bool "NET_SendDatagram" (net/send-datagram sock addr (int port) p (int n))))))
  nil)

(defn receive
  "NET_ReceiveDatagram: the next datagram that has arrived as {:data byte-array
  :address addr :address-string s :port n}, or nil when none has (it never
  blocks). :address is a reference for replying; unref-address! it when done."
  [sock]
  (with-outs [pp :pointer]
    (core/check-bool "NET_ReceiveDatagram" (net/receive-datagram sock pp))
    (let [d (ffi/read pp :pointer)]
      (when-not (ffi/null? d)
        (let [{:keys [addr port buf buflen]} (ffi/read d net/datagram)
              result {:data (ffi/read-array buf buflen)
                      :address (net/ref-address addr)
                      :address-string (net/get-address-string addr)
                      :port port}]
          (net/destroy-datagram d)
          result)))))

(defsdl close-datagram-socket! net/destroy-datagram-socket)

;; ---------------------------------------------------------------------------
;; waiting on many sockets
;; ---------------------------------------------------------------------------

(defn wait-input
  "NET_WaitUntilInputAvailable: wait up to `ms` until any of `sockets` (stream
  sockets, servers or datagram sockets) has something to read or accept; answers
  how many do."
  [sockets ms]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/alloc-pointers a sockets)
          got (net/wait-until-input-available p (int n) (int ms))]
      (when (neg? got) (throw (core/sdl-error "NET_WaitUntilInputAvailable")))
      got)))

;; ---------------------------------------------------------------------------
;; testing aids
;; ---------------------------------------------------------------------------

(defn simulate-address-resolution-loss! "Fail this percent of resolutions (0-100)." [pct] (net/simulate-address-resolution-loss (int pct)) nil)
(defn simulate-stream-packet-loss! "Drop this percent of the socket's packets (0-100)." [sock pct] (net/simulate-stream-packet-loss sock (int pct)) nil)
(defn simulate-datagram-packet-loss! "Drop this percent of the socket's datagrams (0-100)." [sock pct] (net/simulate-datagram-packet-loss sock (int pct)) nil)
