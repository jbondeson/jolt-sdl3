(ns examples.sdl.renderer.affine-textures
  "Port of SDL's examples/renderer/19-affine-textures: draw a spinning cube whose
  faces are one texture, each face drawn as an affine transform of it.

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
  (sdl/set-app-metadata! "Example Renderer Affine Textures" "1.0" "com.example.renderer-affine-textures")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/affine-textures" window-width window-height :resizable)]
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

(defn iterate [state]
  (let [{:keys [renderer texture]} @state
        x0 (* 0.5 window-width)
        y0 (* 0.5 window-height)
        px (/ (min window-width window-height) (Math/sqrt 3.0))
        now (timer/ticks)
        rad (* (/ (mod now 2000) 2000.0) Math/PI 2)
        cos (Math/cos rad)
        sin (Math/sin rad)
        ;; a rotation of `rad` around the unit axis k
        k [(/ 3.0 (Math/sqrt 50.0)) (/ 4.0 (Math/sqrt 50.0)) (/ 5.0 (Math/sqrt 50.0))]
        [k0 k1 k2] k
        c1 (- 1.0 cos)
        mat [(+ cos (* c1 k0 k0))        (+ (* (- sin) k2) (* c1 k0 k1)) (+ (* sin k1) (* c1 k0 k2))
             (+ (* sin k2) (* c1 k0 k1)) (+ cos (* c1 k1 k1))        (+ (* (- sin) k0) (* c1 k1 k2))
             (+ (* (- sin) k1) (* c1 k0 k2)) (+ (* sin k0) (* c1 k1 k2)) (+ cos (* c1 k2 k2))]
        ;; the cube's eight corners, rotated and projected flat: [x y] each
        corners (vec (for [i (range 8)]
                       (let [x (if (pos? (bit-and i 1)) -0.5 0.5)
                             y (if (pos? (bit-and i 2)) -0.5 0.5)
                             z (if (pos? (bit-and i 4)) -0.5 0.5)]
                         [(+ (* (mat 0) x) (* (mat 1) y) (* (mat 2) z))
                          (+ (* (mat 3) x) (* (mat 4) y) (* (mat 5) z))])))
        point (fn [idx] (let [[cx cy] (corners idx)] {:x (+ x0 (* px cx)) :y (+ y0 (* px cy))}))]
    (r/set-draw-color! renderer 0x42 0x87 0xf5 255) ; light blue background.
    (r/clear! renderer)

    ;; each of the six faces is the texture mapped onto three corners: where its
    ;; top-left, top-right and bottom-left land. Faces pointing away are skipped.
    (doseq [i (range 1 7)]
      (let [dir (bit-and 3 (if (pos? (bit-and i 4)) (bit-not i) i))
            odd (bit-xor (bit-and i 1) (bit-shift-right (bit-and i 2) 1) (bit-shift-right (bit-and i 4) 2))]
        (when-not (< 0 (* (if (pos? odd) 1.0 -1.0) (mat (+ 5 dir))))
          (let [origin-index (bit-shift-left 1 (mod (dec dir) 3))
                right-index (bit-or (bit-shift-left 1 (mod (+ dir odd) 3)) origin-index)
                down-index (bit-or (bit-shift-left 1 (mod (+ dir (bit-xor odd 1)) 3)) origin-index)
                [origin-index right-index down-index]
                (if (zero? odd)
                  [(bit-xor origin-index 7) (bit-xor right-index 7) (bit-xor down-index 7)]
                  [origin-index right-index down-index])]
            (r/render-texture-affine! renderer texture nil (point origin-index) (point right-index) (point down-index))))))

    (r/present! renderer)
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
