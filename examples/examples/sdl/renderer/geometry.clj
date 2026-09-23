(ns examples.sdl.renderer.geometry
  "Port of SDL's examples/renderer/10-geometry: draw triangles from vertices,
  colored per vertex, textured, and indexed.

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
  (sdl/set-app-metadata! "Example Renderer Geometry" "1.0" "com.example.renderer-geometry")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/geometry" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      ;; Textures are pixel data that we upload to the video hardware for fast
      ;; drawing. Lots of 2D engines refer to these as "sprites." We'll do a static
      ;; texture (upload once, draw many times) with data from a png file.

      ;; An SDL_Surface is pixel data the CPU can access. An SDL_Texture is pixel
      ;; data the GPU can access. Load a .png into a surface, move it to a texture
      ;; from there.
      (let [surf (surface/load-png (assets/path "sample.png"))
            texture (r/create-texture-from-surface renderer surf)]
        (swap! state assoc :window window :renderer renderer :texture texture)
        (surface/destroy-surface! surf))              ; done with this, the texture has a copy of the pixels now.
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(def ^:private white [1.0 1.0 1.0 1.0])

(defn iterate [state]
  (let [{:keys [renderer texture]} @state
        now (timer/ticks)
        ;; we'll have the triangle grow and shrink over a few seconds.
        direction (if (>= (mod now 2000) 1000) 1.0 -1.0)
        scale (* (/ (- (mod now 1000) 500) 500.0) direction)
        size (+ 200.0 (* 200.0 scale))]
    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 0 0 0 255)          ; black, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Draw a single triangle with a different color at each vertex. Center this
    ;; one and make it grow and shrink. You always draw triangles with this, but
    ;; you can string triangles together to form polygons.
    (r/render-geometry! renderer nil
                        [{:position [(/ window-width 2.0) (/ (- window-height size) 2.0)] :color [1.0 0.0 0.0 1.0]}
                         {:position [(/ (+ window-width size) 2.0) (/ (+ window-height size) 2.0)] :color [0.0 1.0 0.0 1.0]}
                         {:position [(/ (- window-width size) 2.0) (/ (+ window-height size) 2.0)] :color [0.0 0.0 1.0 1.0]}])

    ;; you can also map a texture to the geometry! Texture coordinates go from 0.0
    ;; to 1.0. That will be the location in the texture bound to this vertex.
    (let [vertices [{:position [10.0 10.0] :color white :tex-coord [0.0 0.0]}
                    {:position [150.0 10.0] :color white :tex-coord [1.0 0.0]}
                    {:position [10.0 150.0] :color white :tex-coord [0.0 1.0]}]]
      (r/render-geometry! renderer texture vertices)

      ;; Did that only draw half of the texture? You can do multiple triangles
      ;; sharing some vertices, using indices, to get the whole thing on the screen:

      ;; Let's just move this over so it doesn't overlap...
      (let [moved (mapv #(update-in % [:position 0] + 450) vertices)
            ;; we need one more vertex, since the two triangles can share two of them.
            vertices (conj moved {:position [600.0 150.0] :color white :tex-coord [1.0 1.0]})]
        ;; And an index to tell it to reuse some of the vertices between
        ;; triangles... 4 vertices, but 6 actual places they used. Indices need
        ;; less bandwidth to transfer and can reorder vertices easily!
        (r/render-geometry! renderer texture vertices [0 1 2 1 2 3])))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
