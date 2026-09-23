(ns examples.showcase.hello
  "The smallest useful program: a window, a rect, and a loop that quits on
  close or Escape. `jolt showcase hello` runs it."
  (:require [sdl3.core :as sdl]
            [sdl3.video :as video]
            [sdl3.render :as r]
            [sdl3.events :as ev]))

(defn- quit-event? [e]
  (or (= :quit (:type e))
      (and (= :key-down (:type e)) (= :escape (:key e)))))

(defn -main [& _]
  (sdl/with-sdl [:video]
    (let [[win ren] (r/create-window-and-renderer "jolt-sdl3 hello" 640 480 [:resizable])]
      (r/set-vsync! ren true)
      (loop []
        (let [quit? (some quit-event? (ev/poll-all!))]
          (r/set-draw-color! ren 24 32 56)
          (r/clear! ren)
          (r/set-draw-color! ren 240 200 60)
          (r/fill-rect! ren 270 190 100 100)
          (r/set-draw-color! ren 230 230 230)
          (r/debug-text! ren 10 10 (str "hello from jolt on SDL " (:string (sdl/version)) " — esc quits"))
          (r/present! ren)
          (when-not quit? (recur))))
      (r/destroy-renderer! ren)
      (video/destroy-window! win))))
