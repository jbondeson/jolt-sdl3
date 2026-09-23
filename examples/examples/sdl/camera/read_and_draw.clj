(ns examples.sdl.camera.read-and-draw
  "Port of SDL's examples/camera/01-read-and-draw: read frames from a camera and
  draw them to the screen.

  This is a very simple approach that is often Good Enough. You can get
  fancier with this: multiple cameras, front/back facing cameras on phones,
  color spaces, choosing formats and framerates...this just requests
  _anything_ and uses what it gets, resizing the window as appropriate.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.camera :as cam]
            [sdl3.log :as log]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.video :as video]
            [examples.sdl.app :as app]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Camera Read and Draw" "1.0" "com.example.camera-read-and-draw")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :camera)
    (let [[window renderer] (r/create-window-and-renderer "examples/camera/read-and-draw" 640 480 :resizable)
          devices (cam/cameras)]
      (swap! state assoc :window window :renderer renderer :texture nil)
      (if (empty? devices)
        (do (log/log! "Couldn't find any camera devices! Please connect a camera and try again.")
            :failure)
        (do (swap! state assoc :camera (cam/open (first devices))) ; just take the first thing we see in any format it wants.
            :continue)))))

(defn event [_ e]
  (case (:type e)
    :quit :success
    :camera-device-approved (do (log/log! "Camera use approved by user!") :continue)
    :camera-device-denied (do (log/log! "Camera use denied by user!") :failure)
    :continue))

(defn iterate [state]
  (let [{:keys [window renderer camera]} @state]
    (cam/with-frame [f camera]
      (when-let [frame (:surface f)]
        ;; Some platforms (like Emscripten) don't know _what_ the camera offers
        ;; until the user gives permission, so we build the texture and resize
        ;; the window when we get a first frame from the camera.
        (let [w (surface/width frame) h (surface/height frame)]
          (when-not (:texture @state)
            (video/set-window-size! window w h) ; Resize the window to match
            (r/set-logical-presentation! renderer w h :letterbox)
            (swap! state assoc :texture (r/create-texture renderer (surface/format frame) :streaming w h)))
          (r/update-texture! (:texture @state) nil (surface/pixels frame) (surface/pitch frame)))))
    (r/set-draw-color! renderer 0x99 0x99 0x99 255)
    (r/clear! renderer)
    (when-let [t (:texture @state)] ; draw the latest camera frame, if available.
      (r/render-texture! renderer t))
    (r/present! renderer)
    :continue))

(defn quit [state _]
  (let [{:keys [camera texture]} @state]
    (when camera (cam/close! camera))
    (when texture (r/destroy-texture! texture))))
  ;; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
