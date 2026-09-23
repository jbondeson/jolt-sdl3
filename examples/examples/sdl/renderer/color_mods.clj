(ns examples.sdl.renderer.color-mods
  "Port of SDL's examples/renderer/11-color-mods: draw a texture three times with
  different color modulation, one of them cycling over time.

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
  (sdl/set-app-metadata! "Example Renderer Color Mods" "1.0" "com.example.renderer-color-mods")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/color-mods" window-width window-height :resizable)]
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
        now (/ (timer/ticks) 1000.0)                 ; convert from milliseconds to seconds.
        ;; choose the modulation values for the center texture. The sine wave
        ;; trick makes it fade between colors smoothly.
        red (+ 0.5 (* 0.5 (Math/sin now)))
        green (+ 0.5 (* 0.5 (Math/sin (+ now (/ (* Math/PI 2) 3)))))
        blue (+ 0.5 (* 0.5 (Math/sin (+ now (/ (* Math/PI 4) 3)))))
        size {:w texture-width :h texture-height}]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Just draw the static texture a few times. You can think of it like a
    ;; stamp, there isn't a limit to the number of times you can draw with it.

    ;; Color modulation multiplies each pixel's red, green, and blue intensities
    ;; by the mod values, so multiplying by 1.0 will leave a color intensity
    ;; alone, 0.0 will shut off that color completely, etc.

    ;; top left; let's make this one blue!
    (r/set-texture-color-mod-float! texture 0.0 0.0 1.0)   ; kill all red and green.
    (r/render-texture! renderer texture nil (merge {:x 0 :y 0} size))

    ;; center this one, and have it cycle through red/green/blue modulations.
    (r/set-texture-color-mod-float! texture red green blue)
    (r/render-texture! renderer texture nil (merge {:x (/ (- window-width texture-width) 2.0)
                                                    :y (/ (- window-height texture-height) 2.0)}
                                                   size))

    ;; bottom right; let's make this one red!
    (r/set-texture-color-mod-float! texture 1.0 0.0 0.0)   ; kill all green and blue.
    (r/render-texture! renderer texture nil (merge {:x (- window-width texture-width)
                                                    :y (- window-height texture-height)}
                                                   size))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
