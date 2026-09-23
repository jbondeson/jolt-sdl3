(ns examples.sdl.renderer.rectangles
  "Port of SDL's examples/renderer/05-rectangles: draw some rectangles, outlined
  and filled, one at a time and in batches, growing and shrinking over time.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Rectangles" "1.0" "com.example.renderer-rectangles")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/rectangles" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      (swap! state assoc :window window :renderer renderer)
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer]} @state
        now (timer/ticks)
        ;; we'll have the rectangles grow and shrink over a few seconds.
        direction (if (>= (mod now 2000) 1000) 1.0 -1.0)
        scale (* (/ (- (mod now 1000) 500) 500.0) direction)]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Rectangles are comprised of set of X and Y coordinates, plus width and
    ;; height. (0, 0) is the top left of the window, and larger numbers go down
    ;; and to the right.

    ;; Let's draw a single rectangle (square, really).
    (let [size (+ 100 (* 100 scale))]
      (r/set-draw-color! renderer 255 0 0 255)      ; red, full alpha
      (r/draw-rect! renderer {:x 100 :y 100 :w size :h size}))

    ;; Now let's draw several rectangles with one function call.
    (r/set-draw-color! renderer 0 255 0 255)        ; green, full alpha
    (r/draw-rects! renderer (for [i (range 3)]      ; draw three rectangles at once
                              (let [size (* (inc i) 50.0)
                                    wh (+ size (* size scale))]
                                {:x (/ (- window-width wh) 2) :y (/ (- window-height wh) 2) :w wh :h wh})))

    ;; those were rectangle _outlines_, really. You can also draw _filled_ rectangles!
    (r/set-draw-color! renderer 0 0 255 255)        ; blue, full alpha
    (r/fill-rect! renderer {:x 400 :y 50 :w (+ 100 (* 100 scale)) :h (+ 50 (* 50 scale))})

    ;; ...and also fill a bunch of rectangles at once...
    (r/set-draw-color! renderer 255 255 255 255)    ; white, full alpha
    (r/fill-rects! renderer (for [i (range 16)]
                              (let [w (/ window-width 16.0)
                                    h (* i 8.0)]
                                {:x (* i w) :y (- window-height h) :w w :h h})))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
