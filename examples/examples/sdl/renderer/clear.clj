(ns examples.sdl.renderer.clear
  "Port of SDL's examples/renderer/01-clear: create a window and renderer and
  clear it every frame to a color that fades smoothly over time.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Clear" "1.0" "com.example.renderer-clear")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/clear" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      (swap! state assoc :window window :renderer renderer)
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer]} @state
        now (/ (timer/ticks) 1000.0)
        ;; choose the color for the frame we will draw. The sine wave trick makes it
        ;; fade between colors smoothly.
        red (+ 0.5 (* 0.5 (Math/sin now)))
        green (+ 0.5 (* 0.5 (Math/sin (+ now (/ (* Math/PI 2) 3)))))
        blue (+ 0.5 (* 0.5 (Math/sin (+ now (/ (* Math/PI 4) 3)))))]
    (r/set-draw-color-float! renderer red green blue 1.0)
    (r/clear! renderer)
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
