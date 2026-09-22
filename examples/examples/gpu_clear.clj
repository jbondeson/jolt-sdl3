(ns examples.gpu-clear
  "The GPU API's smallest frame loop: claim a window, acquire its swapchain
  texture each frame, and clear it to a color that cycles over time. Needs no
  shaders, so it runs on every driver. Escape or closing the window quits.
  `jolt gpu-clear` runs it."
  (:require [sdl3.core :as sdl]
            [sdl3.video :as video]
            [sdl3.events :as ev]
            [sdl3.gpu :as gpu]
            [sdl3.timer :as timer]))

(defn -main [& _]
  (sdl/with-sdl [:video]
    (let [dev (gpu/create-device {:shader-formats [:spirv :msl :dxil]})
          win (video/create-window "jolt-sdl3 gpu clear" 640 480 [:resizable])]
      (gpu/claim-window! dev win)
      (println "GPU driver:" (gpu/device-driver dev))
      (loop []
        (let [quit? (some #(or (= :quit (:type %))
                               (and (= :key-down (:type %)) (= :escape (:key %))))
                          (ev/poll-all!))
              t (timer/seconds)
              cb (gpu/acquire-command-buffer dev)]
          (when-let [{:keys [texture]} (gpu/wait-and-acquire-swapchain-texture cb win)]
            (gpu/end-render-pass!
             (gpu/begin-render-pass cb [{:texture texture
                                         :clear-color [(+ 0.5 (* 0.5 (Math/sin t)))
                                                       (+ 0.5 (* 0.5 (Math/sin (+ t 2.1))))
                                                       (+ 0.5 (* 0.5 (Math/sin (+ t 4.2))))
                                                       1.0]
                                         :load-op :clear :store-op :store}])))
          (gpu/submit! cb)
          (when-not quit? (recur))))
      (gpu/release-window! dev win)
      (video/destroy-window! win)
      (gpu/destroy-device! dev))))
