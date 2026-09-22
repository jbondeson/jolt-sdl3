(ns sdl3.storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.storage :as st]))

(defn- temp-dir []
  (let [d (java.io.File. (str (System/getProperty "java.io.tmpdir") "/jolt-sdl3-storage-" (System/nanoTime)))]
    (.mkdirs d)
    (str d)))

(deftest file-storage
  (st/with-storage [s (st/open-file (temp-dir))]
    (st/wait-ready! s)
    (is (st/ready? s))
    (st/write-file! s "a.txt" "hello")
    (st/mkdir! s "sub")
    (st/write-file! s "sub/b.edn" (.getBytes "{:x 1}"))
    (is (= "hello" (st/read-string-file s "a.txt")))
    (is (= [123 58 120 32 49 125] (vec (st/read-file s "sub/b.edn"))))
    (is (= 5 (st/file-size s "a.txt")))
    (is (= ["a.txt" "sub"] (sort (st/list-dir s ""))))
    (testing "glob matches the relative path; * does not cross /"
      (is (= ["a.txt" "sub" "sub/b.edn"] (sort (st/glob s "" nil))))
      (is (= ["sub/b.edn"] (st/glob s "" "*/*.edn")))
      (is (= [] (st/glob s "" "*.edn"))))
    (let [info (st/path-info s "a.txt")]
      (is (= :file (:type info)))
      (is (= 5 (:size info)))
      (is (pos? (:modify-time info))))
    (is (= :directory (:type (st/path-info s "sub"))))
    (is (nil? (st/path-info s "nope")))
    (st/rename! s "a.txt" "c.txt")
    (st/copy! s "c.txt" "d.txt")
    (st/remove! s "c.txt")
    (is (= ["d.txt" "sub"] (sort (st/list-dir s ""))))
    (is (not (st/exists? s "a.txt")))
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"SDL_GetStorageFileSize failed" (st/read-file s "nope")))))

(deftest user-storage-opens
  (st/with-storage [s (st/open-user "jolt-sdl3" "storage-test")]
    (st/wait-ready! s)
    (st/write-file! s "probe.txt" "ok")
    (is (= "ok" (st/read-string-file s "probe.txt")))
    (st/remove! s "probe.txt")))
