(ns sdl3.tray-dialog-test
  "Tray menus are driven with click!, which runs callbacks as a user click would.
  Dialogs are modal and wait for a person, so only their argument encoding is
  tested here."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.dialog :as dlg]
            [sdl3.tray :as tray]
            [sdl3.raw.dialog :as raw-dialog]))

(use-fixtures :each (fn [f] (sdl/init! :video) (try (f) (finally (sdl/quit!)))))

(deftest tray-menus
  (let [t (tray/create-tray nil "jolt-sdl3 test")
        clicks (atom [])]
    (try
      (let [[a _ b more] (tray/build-menu! (tray/menu t)
                                           [{:label "A" :on-click #(swap! clicks conj (tray/label %))}
                                            :separator
                                            {:label "B" :checkbox true :on-click #(swap! clicks conj [(tray/label %) (tray/checked? %)])}
                                            {:label "More" :submenu [{:label "deep" :on-click #(swap! clicks conj (tray/label %))}]}
                                            {:label "Off" :disabled true}])
            deep (first (tray/entries (tray/submenu more)))]
        (is (= 5 (count (tray/entries (tray/menu t)))))
        (tray/click! a)
        (tray/click! b)
        (tray/click! deep)
        (is (= ["A" ["B" true] "deep"] @clicks))
        (is (tray/checked? b))
        (is (not (tray/enabled? (last (tray/entries (tray/menu t))))))
        (is (= t (tray/tray-of deep)))
        (tray/set-label! a "A2")
        (is (= "A2" (tray/label a)))
        (tray/on-click! a nil)
        (tray/click! a)
        (is (= 3 (count @clicks)) "a removed callback no longer fires"))
      (finally (tray/destroy! t)))))

(deftest dialog-filters-encode
  (let [[_ _ fp n _] (#'sdl3.dialog/setup {:filters [{:name "Images" :pattern "png;jpg"} {:name "All" :pattern "*"}]} nil)
        size (ffi/layout-size raw-dialog/dialog-file-filter)
        read-filter (fn [i] (-> (ffi/read (ffi/slice fp (* i size)) raw-dialog/dialog-file-filter)
                                (update :name ffi/ptr->string)
                                (update :pattern ffi/ptr->string)))]
    (is (= 2 n))
    (is (= {:name "Images" :pattern "png;jpg"} (read-filter 0)))
    (is (= {:name "All" :pattern "*"} (read-filter 1)))))
