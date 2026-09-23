(ns examples.sdl.renderer.points
  "Port of SDL's examples/renderer/04-points: draw a bunch of single points,
  moving them across the screen every frame.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(def window-width 640)
(def window-height 480)
(def num-points 500)
(def min-pixels-per-second 30)                     ; move at least this many pixels per second.
(def max-pixels-per-second 60)                     ; move this many pixels per second at most.

(defn- random-speed []
  (+ min-pixels-per-second (* (rand) (- max-pixels-per-second min-pixels-per-second))))

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Points" "1.0" "com.example.renderer-points")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/points" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      ;; set up the data for a bunch of points. Points are plotted as a set of X
      ;; and Y coordinates. (0, 0) is the top left of the window, and larger
      ;; numbers go down and to the right.
      (swap! state assoc :window window :renderer renderer
             :points (vec (repeatedly num-points
                                      (fn [] {:x (* (rand) window-width) :y (* (rand) window-height)
                                              :speed (random-speed)})))
             :last-time (timer/ticks))
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn- move [elapsed {:keys [x y speed] :as p}]
  (let [distance (* elapsed speed)
        x (+ x distance)
        y (+ y distance)]
    (if (or (>= x window-width) (>= y window-height))
      ;; off the screen; restart it elsewhere!
      (if (zero? (rand-int 2))
        {:x (* (rand) window-width) :y 0.0 :speed (random-speed)}
        {:x 0.0 :y (* (rand) window-height) :speed (random-speed)})
      (assoc p :x x :y y))))

(defn iterate [state]
  (let [{:keys [renderer last-time]} @state
        now (timer/ticks)
        elapsed (/ (- now last-time) 1000.0)            ; seconds since last iteration
        ;; let's move all our points a little for a new frame.
        points (mapv #(move elapsed %) (:points @state))]
    (swap! state assoc :points points :last-time now)

    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.
    (r/set-draw-color! renderer 255 255 255 255)    ; white, full alpha
    (r/draw-points! renderer points)                ; draw all the points!

    ;; You can also draw single points with draw-point!, but it's cheaper
    ;; (sometimes significantly so) to do them all at once.
    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
