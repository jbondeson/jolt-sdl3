(ns sdl3.thread-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.thread :as th]))

(deftest threads-return-and-throw
  (let [t (th/create-thread "sum" (fn [] (reduce + (range 100000))))]
    (is (string? (th/thread-name t)))
    (is (= 4999950000 (th/wait-thread! t))))
  (let [t (th/create-thread "boom" (fn [] (throw (ex-info "boom" {:x 1}))))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"boom" (th/wait-thread! t))))
  (is (pos? (th/current-thread-id))))

(deftest locks-semaphores-and-atomics
  (let [m (th/create-mutex)
        counter (th/atomic-int 0)
        sem (th/create-semaphore)
        plain (atom 0)
        ts (doall (for [i (range 4)]
                    (th/create-thread (str "w" i)
                                      (fn []
                                        (dotimes [_ 500]
                                          (th/add-int! counter 1)
                                          (th/with-lock [m] (swap! plain inc)))
                                        (th/signal-semaphore! sem)
                                        i))))]
    (dotimes [_ 4] (th/wait-semaphore! sem))
    (is (= [0 1 2 3] (mapv th/wait-thread! ts)))
    (is (= 2000 (th/get-int counter)))
    (is (= 2000 @plain))
    (is (true? (th/cas-int! counter 2000 7)))
    (is (false? (th/cas-int! counter 2000 8)))
    (is (= 7 (th/get-int counter)))
    (is (false? (th/wait-semaphore-timeout! sem 10)))
    (is (true? (th/try-lock-mutex! m)))
    (th/unlock-mutex! m)
    (th/free! counter)
    (th/destroy-mutex! m)
    (th/destroy-semaphore! sem)))

(deftest conditions-and-rw-locks
  (let [m (th/create-mutex)
        cnd (th/create-condition)
        ready (atom false)
        t (th/create-thread "waiter"
                            (fn []
                              (th/with-lock [m]
                                (loop [] (when-not @ready (th/wait-condition! cnd m) (recur))))
                              :woken))]
    (th/with-lock [m] (reset! ready true) (th/signal-condition! cnd))
    (is (= :woken (th/wait-thread! t)))
    (is (false? (th/with-lock [m] (th/wait-condition-timeout! cnd m 10))))
    (th/destroy-condition! cnd)
    (th/destroy-mutex! m))
  (let [l (th/create-rw-lock)]
    (is (= :r (th/with-read-lock [l] :r)))
    (is (= :w (th/with-write-lock [l] :w)))
    (th/destroy-rw-lock! l))
  (let [s (th/spinlock)]
    (is (= :s (th/with-spinlock [s] :s)))
    (th/free! s)))
