(ns sdl3.net-test
  "SDL_net over the loopback interface. Skipped, with a note, when libSDL3_net is
  not installed."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [sdl3.net :as net]
            [sdl3.test-util :as tu]))

(def ^:dynamic *lo* nil)

(use-fixtures :once
  (fn [f]
    (if-not (net/available?)
      (tu/skip-or-fail "sdl3.net-test" "libSDL3_net not installed")
      (net/with-net
        (let [lo (net/resolve! "127.0.0.1" 5000)]
          (try (binding [*lo* lo] (f))
               (finally (net/unref-address! lo))))))))

(defn- free-port [] (+ 40000 (rand-int 20000)))

(defn- poll [f]
  (loop [i 0] (or (f) (when (< i 300) (Thread/sleep 10) (recur (inc i))))))

(deftest addresses
  (is (= 3 (:major (net/version))))
  (is (= "127.0.0.1" (net/address-string *lo*)))
  (is (= :success (net/address-status *lo*)))
  (is (pos? (alength ^bytes (net/address-bytes *lo*))))
  (is (zero? (net/compare-addresses *lo* *lo*)))
  (is (some #{"127.0.0.1"} (net/local-addresses)))
  (is (thrown? clojure.lang.ExceptionInfo (net/resolve! "no-such-host.invalid" 10000))
      ".invalid never resolves"))

(deftest tcp-round-trip
  (let [port (free-port)
        server (net/listen *lo* port)
        client (net/connect *lo* port)]
    (try
      (is (= :success (net/wait-connected client 5000)))
      (let [peer (poll #(net/accept server))]
        (is (some? peer))
        (try
          (net/write! client "hello over tcp")
          (is (zero? (net/wait-drained client 2000)))
          (is (= 1 (net/wait-input [peer] 2000)))
          (is (= "hello over tcp" (String. ^bytes (net/read peer 100) "UTF-8")))
          (net/write! peer (.getBytes "reply"))
          (net/wait-input [client] 2000)
          (is (= "reply" (String. ^bytes (net/read client 100) "UTF-8")))
          (is (zero? (alength ^bytes (net/read client 100))) "nothing more has arrived")
          (let [a (net/socket-address peer)]
            (is (= "127.0.0.1" (net/address-string a)))
            (net/unref-address! a))
          (finally (net/destroy-socket! peer))))
      (is (nil? (net/accept server)) "no second client is waiting")
      (finally (net/destroy-socket! client) (net/close-server! server)))))

(deftest udp-round-trip
  (let [port (free-port)
        a (net/datagram-socket *lo* port)
        b (net/datagram-socket *lo* 0)]
    (try
      (net/send! b *lo* port "udp hi")
      (net/wait-input [a] 2000)
      (let [{:keys [data address address-string port]} (poll #(net/receive a))]
        (is (= "udp hi" (String. ^bytes data "UTF-8")))
        (is (= "127.0.0.1" address-string))
        (net/send! a address port "udp reply")
        (net/unref-address! address))
      (net/wait-input [b] 2000)
      (is (= "udp reply" (String. ^bytes (:data (poll #(net/receive b))) "UTF-8")))
      (is (nil? (net/receive b)))
      (finally (net/close-datagram-socket! a) (net/close-datagram-socket! b)))))
