(ns examples.sdl.renderer.streaming-textures
  "Port of SDL's examples/renderer/07-streaming-textures: update a streaming
  texture's pixels every frame, drawing into it through a temporary surface.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(def texture-size 150)
(def window-width 640)
(def window-height 480)

(defn init [state _]
  (sdl/set-app-metadata! "Example Renderer Streaming Textures" "1.0" "com.example.renderer-streaming-textures")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/renderer/streaming-textures" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :letterbox)
      (swap! state assoc :window window :renderer renderer
             :texture (r/create-texture renderer :rgba8888 :streaming texture-size texture-size))
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer texture]} @state
        now (timer/ticks)
        ;; we'll have some color move around over a few seconds.
        direction (if (>= (mod now 2000) 1000) 1.0 -1.0)
        scale (* (/ (- (mod now 1000) 500) 500.0) direction)]
    ;; To update a streaming texture, you need to lock it first. This gets you
    ;; access to the pixels. Note that this is considered a _write-only_
    ;; operation: the buffer you get from locking might not actually have the
    ;; existing contents of the texture, and you have to write to every locked
    ;; pixel!

    ;; You can use lock-texture to get an array of raw pixels, but we're going to
    ;; use lock-texture-to-surface here, because it wraps that array in a
    ;; temporary SDL_Surface, letting us use the surface drawing functions
    ;; instead of lighting up individual pixels.
    (let [surf (r/lock-texture-to-surface texture nil)
          strip-h (quot texture-size 10)]
      (surface/fill-rect! surf nil (surface/map-rgb surf 0 0 0))   ; make the whole surface black
      (surface/fill-rect! surf {:x 0
                                :y (int (* (- texture-size strip-h) (/ (+ scale 1.0) 2.0)))
                                :w texture-size :h strip-h}
                          (surface/map-rgb surf 0 255 0))           ; make a strip of the surface green
      (r/unlock-texture! texture))                   ; upload the changes (and frees the temporary surface)!

    ;; as you can see from this, rendering draws over whatever was drawn before it.
    (r/set-draw-color! renderer 66 66 66 255)       ; grey, full alpha
    (r/clear! renderer)                             ; start with a blank canvas.

    ;; Just draw the static texture a few times. You can think of it like a
    ;; stamp, there isn't a limit to the number of times you can draw with it.

    ;; Center this one. It'll draw the latest version of the texture we drew
    ;; while it was locked.
    (r/render-texture! renderer texture nil {:x (/ (- window-width texture-size) 2.0)
                                             :y (/ (- window-height texture-size) 2.0)
                                             :w texture-size :h texture-size})

    (r/present! renderer)                           ; put it all on the screen!
    :continue))

(defn quit [state _]
  (some-> (:texture @state) r/destroy-texture!))    ; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
