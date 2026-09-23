(ns examples.sdl.renderer.read-pixels
  "Port of SDL's examples/renderer/17-read-pixels: draw a spinning texture, read
  the rendered frame back from the GPU, turn it black and white on the CPU, and
  draw that in the corner.

  The original C is public domain; so is this port."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Read Pixels" "1.0" "com.example.renderer-read-pixels")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/read-pixels" window-width window-height :resizable)]
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
               :texture-width (surface/width surf) :texture-height (surface/height surf)
               :converted nil :converted-width 0 :converted-height 0)
        (surface/destroy-surface! surf))              ; done with this, the texture has a copy of the pixels now.
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn- black-and-white!
  "Turn each pixel of an RGBA8888 surface into either black or white, in place.
  This is a lousy technique but it works here. In real life, something like
  Floyd-Steinberg dithering might work better:
  https://en.wikipedia.org/wiki/Floyd%E2%80%93Steinberg_dithering

  The pixels are copied out in one block, processed as a byte-array, and copied
  back, rather than read one at a time through the FFI. In memory an RGBA8888
  pixel on a little-endian machine is the bytes A B G R, which is why byte 0 is
  alpha and bytes 1-3 are the color."
  [surf]
  (let [w (surface/width surf)
        h (surface/height surf)
        pitch (surface/pitch surf)
        pixels (surface/pixels surf)
        ^bytes bs (ffi/read-array pixels (* h pitch))]
    (dotimes [y h]
      (let [row (* y pitch)]
        (dotimes [x w]
          (let [p (+ row (* 4 x))
                average (quot (+ (bit-and (aget bs (+ p 1)) 0xFF)
                                 (bit-and (aget bs (+ p 2)) 0xFF)
                                 (bit-and (aget bs (+ p 3)) 0xFF))
                              3)]
            (if (zero? average)
              (do (aset bs p (unchecked-byte 0xFF))            ; make pure black pixels red.
                  (aset bs (+ p 3) (unchecked-byte 0xFF))
                  (aset bs (+ p 1) (byte 0))
                  (aset bs (+ p 2) (byte 0)))
              (let [v (unchecked-byte (if (> average 50) 0xFF 0x00))] ; make everything else either black or white.
                (aset bs (+ p 1) v)
                (aset bs (+ p 2) v)
                (aset bs (+ p 3) v)))))))
    (ffi/write-array pixels bs)))

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

    ;; this next whole thing is _super_ expensive. Seriously, don't do this in
    ;; real life.

    ;; Download the pixels of what has just been rendered. This has to wait for
    ;; the GPU to finish rendering it and everything before it, and then make an
    ;; expensive copy from the GPU to system RAM!
    (let [read (r/read-pixels renderer nil)
          ;; This is also expensive, but easier: convert the pixels to a format we want.
          surf (if (#{:rgba8888 :bgra8888} (surface/format read))
                 read
                 (let [converted (surface/convert-surface read :rgba8888)]
                   (surface/destroy-surface! read)
                   converted))
          w (surface/width surf)
          h (surface/height surf)]
      ;; Rebuild the converted texture if the dimensions have changed (window
      ;; resized, etc).
      (when (or (not= w (:converted-width @state)) (not= h (:converted-height @state)))
        (some-> (:converted @state) r/destroy-texture!)
        (swap! state assoc
               :converted (r/create-texture renderer :rgba8888 :streaming w h)
               :converted-width w :converted-height h))

      (black-and-white! surf)

      ;; upload the processed pixels back into a texture.
      (r/update-texture! (:converted @state) nil (surface/pixels surf) (surface/pitch surf))
      (surface/destroy-surface! surf)

      ;; draw the texture to the top-left of the screen.
      (r/render-texture! renderer (:converted @state) nil {:x 0 :y 0 :w (/ window-width 4.0) :h (/ window-height 4.0)}))

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:converted @state) r/destroy-texture!)
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
