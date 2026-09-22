(ns sdl3.core-test
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.core :as sdl]
            [sdl3.consts :as c]
            [sdl3.render :as render]))

(deftest version-and-platform
  (let [{:keys [major minor micro string]} (sdl/version)]
    (is (= 3 major))
    (is (= string (str major "." minor "." micro))))
  (is (string? (sdl/platform)))
  (is (string? (sdl/revision))))

(deftest flags-round-trip
  (is (= 0 (sdl/flags c/init-flags nil)))
  (is (= (c/init-flags :video) (sdl/flags c/init-flags :video)))
  (is (= (bit-or (c/init-flags :video) (c/init-flags :audio)) (sdl/flags c/init-flags [:video [:audio]])))
  (is (= 7 (sdl/flags c/init-flags 7)))
  (is (= #{:video :audio} (sdl/unflag c/init-flags (sdl/flags c/init-flags [:video :audio]))))
  (is (thrown? clojure.lang.ExceptionInfo (sdl/flags c/init-flags :nope)))
  (testing "composite aliases are not reported by unflag"
    (is (= #{:lctrl :rctrl} (sdl/unflag c/keymod (c/keymod :ctrl))))))

(deftest enum-round-trip
  (is (= (c/event-type :quit) (sdl/enum c/event-type :quit)))
  (is (= :quit (sdl/unenum c/event-type-names (c/event-type :quit))))
  (is (= 0x9999 (sdl/unenum c/event-type-names 0x9999))))

(deftest init-quit-and-hints
  (sdl/init! :events)
  (try
    (is (contains? (sdl/was-init) :events))
    (is (true? (sdl/set-hint! :render-vsync true)))
    (is (= "1" (sdl/hint :render-vsync)))
    (is (true? (sdl/hint-boolean :render-vsync false)))
    (is (true? (sdl/reset-hint! :render-vsync)))
    (is (nil? (sdl/hint :render-vsync)))
    (is (true? (sdl/set-hint! "SDL_JOLT_TEST_HINT" "x")) "a hint may be named by its SDL string")
    (is (= "x" (sdl/hint "SDL_JOLT_TEST_HINT")))
    (finally (sdl/quit!)))
  (is (empty? (sdl/was-init))))

(deftest errors-carry-sdl-get-error
  (sdl/init! :events)
  (try
    (let [e (try (render/create-renderer 0) nil
                 (catch clojure.lang.ExceptionInfo e e))]
      (is (some? e))
      (is (= "SDL_CreateRenderer" (:sdl/fn (ex-data e))))
      (is (string? (:sdl/error (ex-data e))))
      (is (re-find #"SDL_CreateRenderer failed" (.getMessage e))))
    (finally (sdl/quit!))))
