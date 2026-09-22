(ns examples.tray
  "A menu bar / system tray icon whose menu opens a native file dialog, and a
  window showing the last choice. Quit from the menu, with Escape, or by
  closing the window. `jolt tray` runs it."
  (:require [sdl3.core :as sdl]
            [sdl3.dialog :as dialog]
            [sdl3.events :as ev]
            [sdl3.render :as r]
            [sdl3.tray :as tray]
            [sdl3.video :as video]))

(defn -main [& _]
  (sdl/with-sdl [:video]
    (let [[win ren] (r/create-window-and-renderer "jolt-sdl3 tray" 640 200 [])
          status (atom "use the tray menu")
          running (atom true)
          t (tray/create-tray nil "jolt-sdl3 tray example")]
      (tray/build-menu! (tray/menu t)
                        [{:label "Open file..."
                          :on-click (fn [_]
                                      (dialog/open-file {:window win
                                                         :filters [{:name "Clojure" :pattern "clj;cljc;edn"}
                                                                   {:name "All files" :pattern "*"}]}
                                                        (fn [{:keys [files error]}]
                                                          (reset! status (cond error (str "error: " error)
                                                                               (empty? files) "canceled"
                                                                               :else (str "chose " (first files)))))))}
                         {:label "Always on top" :checkbox true
                          :on-click (fn [e] (video/set-window-always-on-top! win (tray/checked? e)))}
                         :separator
                         {:label "Quit" :on-click (fn [_] (reset! running false))}])
      (while @running
        (doseq [e (ev/poll-all!)]
          (when (or (= :quit (:type e)) (and (= :key-down (:type e)) (= :escape (:key e))))
            (reset! running false)))
        (r/set-draw-color! ren 24 32 56)
        (r/clear! ren)
        (r/set-draw-color! ren 230 230 230)
        (r/debug-text! ren 10 90 @status)
        (r/present! ren))
      (tray/destroy! t)
      (r/destroy-renderer! ren)
      (video/destroy-window! win))))
