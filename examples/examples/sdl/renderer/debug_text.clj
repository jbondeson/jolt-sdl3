(ns examples.sdl.renderer.debug-text
  "Port of SDL's examples/renderer/18-debug-text: draw text with SDL's built-in
  8x8 debug font, in different colors and scales.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Debug Texture" "1.0" "com.example.renderer-debug-text")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/debug-text" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      (swap! state assoc :window window :renderer renderer)
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer]} @state
        charsize r/debug-text-font-character-size]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    (r/set-draw-color! renderer 255 255 255 255)    ; white, full alpha
    (r/debug-text! renderer 272 100 "Hello world!")
    (r/debug-text! renderer 224 150 "This is some debug text.")

    (r/set-draw-color! renderer 51 102 255 255)     ; light blue, full alpha
    (r/debug-text! renderer 184 200 "You can do it in different colors.")
    (r/set-draw-color! renderer 255 255 255 255)    ; white, full alpha

    (r/set-scale! renderer 4.0 4.0)
    (r/debug-text! renderer 14 65 "It can be scaled.")
    (r/set-scale! renderer 1.0 1.0)
    (r/debug-text! renderer 64 350 "This only does ASCII chars. So this laughing emoji won't draw: 🤣")

    (r/debug-text! renderer (/ (- window-width (* charsize 46)) 2.0) 400
                   (str "(This program has been running for " (quot (timer/ticks) 1000) " seconds.)"))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
