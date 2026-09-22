(ns sdl3.asyncio-test
  (:require [clojure.test :refer [deftest is]]
            [sdl3.asyncio :as aio]))

(defn- temp-path [] (str (System/getProperty "java.io.tmpdir") "/jolt-sdl3-aio-" (System/nanoTime)))
(defn- utf8 [^bytes bs] (String. bs "UTF-8"))

(deftest write-close-load-read
  (let [path (temp-path)
        q (aio/create-queue)]
    (try
      (let [f (aio/open path "w")]
        (aio/write! f 0 "async hello" q :w)
        (is (= {:tag :w :type :write :result :complete :bytes-transferred 11}
               (select-keys (aio/wait-result q 2000) [:tag :type :result :bytes-transferred])))
        (aio/close! f true q :c)
        (is (= [:c :close :complete] ((juxt :tag :type :result) (aio/wait-result q 2000)))))
      (aio/load-file! path q :load)
      (let [r (aio/wait-result q 2000)]
        (is (= :load (:tag r)))
        (is (= "async hello" (utf8 (:data r)))))
      (dotimes [_ 5]
        ;; repeated, since SDL's spurious early wakeups showed up intermittently here
        (let [f (aio/open path "r")]
          (is (= 11 (aio/size f)))
          (aio/read! f 6 5 q :part)
          (let [r (aio/wait-result q 2000)]
            (is (= :part (:tag r)))
            (is (= "hello" (utf8 (:data r)))))
          (aio/close! f false q :c)
          (aio/wait-result q 2000)))
      (is (nil? (aio/poll-result q)))
      (is (nil? (aio/wait-result q 20)) "times out with nothing pending")
      (finally (aio/destroy-queue! q)))))

(deftest signal-wakes-waiters
  (let [q (aio/create-queue)
        waiter (future (aio/wait-result q -1))]
    (Thread/sleep 50)
    (aio/signal-queue! q)
    (is (nil? (deref waiter 2000 :still-waiting)))
    (aio/destroy-queue! q)))

(deftest submission-errors-raise
  (let [q (aio/create-queue)]
    (is (thrown? clojure.lang.ExceptionInfo (aio/load-file! "/nonexistent/jolt-sdl3" q :x)))
    (aio/destroy-queue! q)))
