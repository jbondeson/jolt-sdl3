(ns examples.sdl.renderer.cliprect
  "Port of SDL's examples/renderer/15-cliprect: slide a clipping rectangle around
  the window, so only the piece of a stretched texture inside it gets drawn.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(def window-width 640)
(def window-height 480)
(def cliprect-size 250)
(def cliprect-speed 200)                           ; pixels per second

;; A lot of this program is examples/renderer/02-primitives, so we have a good
;; visual that we can slide a clip rect around. The actual new magic in here is
;; the set-clip-rect! function.

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Clipping Rectangle" "1.0" "com.example.renderer-cliprect")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/cliprect" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      ;; Textures are pixel data that we upload to the video hardware for fast
      ;; drawing. Lots of 2D engines refer to these as "sprites." We'll do a static
      ;; texture (upload once, draw many times) with data from a png file.

      ;; An SDL_Surface is pixel data the CPU can access. An SDL_Texture is pixel
      ;; data the GPU can access. Load a .png into a surface, move it to a texture
      ;; from there.
      (let [surf (surface/load-png (assets/path "sample.png"))
            texture (r/create-texture-from-surface renderer surf)]
        (swap! state assoc :window window :renderer renderer :texture texture
               :position [0.0 0.0] :direction [1.0 1.0] :last-time (timer/ticks))
        (surface/destroy-surface! surf))              ; done with this, the texture has a copy of the pixels now.
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn- slide
  "Move one coordinate by `distance`, bouncing between -size and `limit`."
  [pos dir distance limit]
  (let [pos (+ pos (* distance dir))]
    (cond (< pos (- cliprect-size)) [(- cliprect-size) 1.0]
          (>= pos limit) [(dec limit) -1.0]
          :else [pos dir])))

(defn iterate [state]
  (let [{:keys [renderer texture position direction last-time]} @state
        [px py] position
        [dx dy] direction
        ;; the clip rect for this frame, from where it was left last frame
        cliprect {:x (Math/round (double px)) :y (Math/round (double py)) :w cliprect-size :h cliprect-size}
        now (timer/ticks)
        elapsed (/ (- now last-time) 1000.0)            ; seconds since last iteration
        distance (* elapsed cliprect-speed)
        ;; Set a new clipping rectangle position
        [px dx] (slide px dx distance window-width)
        [py dy] (slide py dy distance window-height)]
    (r/set-clip-rect! renderer cliprect)
    (swap! state assoc :position [px py] :direction [dx dy] :last-time now)

    ;; okay, now draw!

    ;; Note that clear! is _not_ affected by the clipping rectangle!
    (r/set-draw-color! renderer 33 33 33 255)       ; grey, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; stretch the texture across the entire window. Only the piece in the
    ;; clipping rectangle will actually render, though!
    (r/render-texture! renderer texture nil nil)

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
