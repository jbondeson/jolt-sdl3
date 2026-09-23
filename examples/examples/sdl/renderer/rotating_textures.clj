(ns examples.sdl.renderer.rotating-textures
  "Port of SDL's examples/renderer/08-rotating-textures: load a .png into a
  texture and draw it spinning.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Rotating Textures" "1.0" "com.example.renderer-rotating-textures")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/rotating-textures" window-width window-height :resizable)]
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
               :texture-width (surface/width surf) :texture-height (surface/height surf))
        (surface/destroy-surface! surf))              ; done with this, the texture has a copy of the pixels now.
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer texture texture-width texture-height]} @state
        now (timer/ticks)
        ;; we'll have a texture rotate around over 2 seconds (2000 milliseconds).
        ;; 360 degrees in a circle!
        rotation (* (/ (mod now 2000) 2000.0) 360.0)]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Center this one, and draw it with some rotation so it spins! Rotate it
    ;; around the center of the texture; you can rotate it from a different
    ;; point, too!
    (r/render-texture-rotated! renderer texture nil
                               {:x (/ (- window-width texture-width) 2.0)
                                :y (/ (- window-height texture-height) 2.0)
                                :w texture-width :h texture-height}
                               rotation
                               {:x (/ texture-width 2.0) :y (/ texture-height 2.0)}
                               :none)

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
