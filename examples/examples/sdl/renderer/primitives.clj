(ns examples.sdl.renderer.primitives
  "Port of SDL's examples/renderer/02-primitives: draw some lines, rectangles and
  points every frame.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.rect :as rect]
            [jolt.ffi :as ffi]
            [examples.sdl.app :as app]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Primitives" "1.0" "com.example.renderer-primitives")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/primitives" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; set up some random points, once, in native memory we reuse every frame
      (swap! state assoc :window window :renderer renderer
             :points (rect/fpoints (ffi/global-arena)
                                   (repeatedly 500 (fn [] [(+ 100 (* 440 (rand))) (+ 100 (* 280 (rand)))]))))
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer points]} @state]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 33 33 33 255)       ; dark gray, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; draw a filled rectangle in the middle of the canvas.
    (r/set-draw-color! renderer 0 0 255 255)        ; blue, full alpha
    (r/fill-rect! renderer {:x 100 :y 100 :w 440 :h 280})

    ;; draw some points across the canvas.
    (r/set-draw-color! renderer 255 0 0 255)        ; red, full alpha
    (r/draw-points! renderer points)

    ;; draw a unfilled rectangle in-set a little bit.
    (r/set-draw-color! renderer 0 255 0 255)        ; green, full alpha
    (r/draw-rect! renderer {:x 130 :y 130 :w 380 :h 220})

    ;; draw two lines in an X across the whole canvas.
    (r/set-draw-color! renderer 255 255 0 255)      ; yellow, full alpha
    (r/draw-line! renderer 0 0 640 480)
    (r/draw-line! renderer 0 480 640 0)

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
