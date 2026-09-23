(ns examples.sdl.renderer.viewport
  "Port of SDL's examples/renderer/14-viewport: draw a texture through different
  viewports, which move the origin and limit where rendering lands.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Viewport" "1.0" "com.example.renderer-viewport")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/viewport" window-width window-height :resizable)]
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
        dst-rect {:x 0 :y 0 :w texture-width :h texture-height}]
    ;; Setting a viewport has the effect of limiting the area that rendering can
    ;; happen, and making coordinate (0, 0) live somewhere else in the window. It
    ;; does _not_ scale rendering to fit the viewport.

    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Draw once with the whole window as the viewport.
    (r/set-viewport! renderer nil)                  ; nil means "use the whole window"
    (r/render-texture! renderer texture nil dst-rect)

    ;; top right quarter of the window.
    (r/set-viewport! renderer {:x (/ window-width 2) :y (/ window-height 2)
                               :w (/ window-width 2) :h (/ window-height 2)})
    (r/render-texture! renderer texture nil dst-rect)

    ;; bottom 20% of the window. Note it clips the width!
    (r/set-viewport! renderer {:x 0 :y (- window-height (quot window-height 5))
                               :w (quot window-width 5) :h (quot window-height 5)})
    (r/render-texture! renderer texture nil dst-rect)

    ;; what happens if you try to draw above the viewport? It should clip!
    (r/set-viewport! renderer {:x 100 :y 200 :w window-width :h window-height})
    (r/render-texture! renderer texture nil (assoc dst-rect :y -50))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
