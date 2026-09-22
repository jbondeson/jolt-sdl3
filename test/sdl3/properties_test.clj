(ns sdl3.properties-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.properties :as p]))

(deftest typed-values-round-trip
  (p/with-properties [g {:window-create-title-string "hi"
                         :window-create-width-number 640
                         "test.float" 1.5
                         "test.bool" true
                         "test.pointer" {:pointer 1234}}]
    (is (= "hi" (p/get g :window-create-title-string)))
    (is (= "hi" (p/get g "SDL.window.create.title")) "a keyword and its SDL string name the same entry")
    (is (= 640 (p/get g :window-create-width-number)))
    (is (= 1.5 (p/get g "test.float")))
    (is (true? (p/get g "test.bool")))
    (is (= 1234 (p/get g "test.pointer")))
    (is (= :number (p/type-of g :window-create-width-number)))
    (is (= :pointer (p/type-of g "test.pointer")))
    (is (nil? (p/type-of g "absent")))
    (is (= :fallback (p/get g "absent" :fallback)))
    (testing "put! nil clears"
      (p/put! g "test.bool" nil)
      (is (not (p/has? g "test.bool"))))
    (is (= #{"SDL.window.create.title" "SDL.window.create.width" "test.float" "test.pointer"}
           (set (p/keys g))))
    (is (= {:window-create-title-string "hi" :window-create-width-number 640 "test.float" 1.5 "test.pointer" 1234}
           (p/->map g)))))

(deftest copy-and-typed-getters
  (p/with-properties [a {"n" 7 "s" "x"}]
    (p/with-properties [b {}]
      (p/copy! a b)
      (is (= 7 (p/get-number b "n" 0)))
      (is (= "x" (p/get-string b "s" nil)))
      (is (= "d" (p/get-string b "missing" "d")))
      (is (false? (p/get-boolean b "missing" false))))))

(deftest rejects-unstorable-values
  (p/with-properties [g {}]
    (is (thrown? clojure.lang.ExceptionInfo (p/put! g "v" [1 2])))))
