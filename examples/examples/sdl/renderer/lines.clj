(ns examples.sdl.renderer.lines
  "Port of SDL's examples/renderer/03-lines: draw some lines every frame, some one
  at a time, some as a connected batch, and a ring of randomly colored ones.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [examples.sdl.app :as app]))

;; Lines (line segments, really) are drawn in terms of points: a set of X and Y
;; coordinates, one set for each end of the line. (0, 0) is the top left of the
;; window, and larger numbers go down and to the right. This isn't how geometry
;; works, but this is pretty standard in 2D graphics.
(def line-points
  [[100 354] [220 230] [140 230] [320 100] [500 230]
   [420 230] [540 354] [400 354] [100 354]])

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Lines" "1.0" "com.example.renderer-lines")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/lines" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      (swap! state assoc :window window :renderer renderer)
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer]} @state]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 100 100 100 255)    ; grey, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; You can draw lines, one at a time, like these brown ones...
    (r/set-draw-color! renderer 127 49 32 255)
    (r/draw-line! renderer 240 450 400 450)
    (r/draw-line! renderer 240 356 400 356)
    (r/draw-line! renderer 240 356 240 450)
    (r/draw-line! renderer 400 356 400 450)

    ;; You can also draw a series of connected lines in a single batch...
    (r/set-draw-color! renderer 0 255 0 255)
    (r/draw-lines! renderer line-points)

    ;; here's a bunch of lines drawn out from a center point in a circle.
    ;; we randomize the color of each line, so it functions as animation.
    (let [size 30.0 x 320.0 y (- 95.0 (/ size 2.0))]
      (dotimes [i 360]
        (let [rad (* i (/ Math/PI 180.0))]
          (r/set-draw-color! renderer (rand-int 256) (rand-int 256) (rand-int 256) 255)
          (r/draw-line! renderer x y (+ x (* (Math/cos rad) size)) (+ y (* (Math/sin rad) size))))))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
