(ns sdl3.io-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.io :as io]))

(defn- utf8 [^bytes bs] (String. bs "UTF-8"))

(deftest memory-streams
  (io/with-io [s (io/dynamic)]
    (is (= 6 (io/write-bytes! s "hello ")))
    (io/write-num! s :u32-le 0x01020304)
    (io/write-num! s :s16-be -2)
    (is (= 12 (io/size s)))
    (is (= [104 101 108 108 111 32 4 3 2 1 -1 -2] (vec (io/contents s))))
    (is (= 0 (io/seek! s 0)))
    (is (= "hello " (utf8 (io/read-bytes s 6))))
    (is (= 0x01020304 (io/read-num s :u32-le)))
    (is (= -2 (io/read-num s :s16-be)))
    (is (= 12 (io/tell s)))
    (is (= [] (vec (io/read-bytes s 4))))
    (is (= :eof (io/status s)))
    (is (thrown? clojure.lang.ExceptionInfo (io/read-num s :u8)))
    (is (= 10 (io/seek! s -2 :end))))
  (io/with-io [s (io/from-bytes (.getBytes "abcdef"))]
    (io/seek! s 2)
    (is (= "cdef" (utf8 (io/read-all s))))))

(deftest files
  (let [path (str (System/getProperty "java.io.tmpdir") "/jolt-sdl3-io-test.bin")]
    (io/save-file! path "saved!")
    (is (= "saved!" (utf8 (io/load-file path))))
    (io/with-io [s (io/from-file path "rb")]
      (is (= 6 (io/size s)))
      (is (= "saved!" (utf8 (io/load-io s false)))))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SDL_IOFromFile failed"
                          (io/from-file "/nonexistent/dir/x" "rb")))))

(deftest streams-from-clojure-functions
  (let [data (.getBytes "custom stream data")
        pos (atom 0)
        closed (atom false)
        s (io/open-io {:size (fn [] (alength data))
                       :seek (fn [off whence]
                               (reset! pos (case whence :set off :cur (+ @pos off) :end (+ (alength data) off))))
                       :read (fn [n]
                               (let [k (min n (- (alength data) @pos))
                                     out (java.util.Arrays/copyOfRange data (int @pos) (int (+ @pos k)))]
                                 (swap! pos + k)
                                 out))
                       :close (fn [] (reset! closed true) true)})]
    (is (= 18 (io/size s)))
    (is (= "custom stream data" (utf8 (io/load-io s false))))
    (io/seek! s 7)
    (is (= "stream" (utf8 (io/read-bytes s 6))))
    (io/close! s)
    (is (true? @closed))))
