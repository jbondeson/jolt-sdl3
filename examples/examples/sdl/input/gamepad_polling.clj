(ns examples.sdl.input.gamepad-polling
  "Port of SDL's examples/input/03-gamepad-polling: look for gamepad input in the
  main loop, and draw it over a picture of a gamepad.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.gamepad :as gp]
            [sdl3.log :as log]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

;; SDL can handle multiple gamepads, but for simplicity, this program only
;; deals with the first gamepad it sees.

(def ^:private char-size 8) ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE
(def ^:private window-width 640)
(def ^:private window-height 480)

;; where to draw the buttons
(def ^:private buttons
  [[:south [497 266 38 38]]
   [:east [550 217 38 38]]
   [:west [445 221 38 38]]
   [:north [499 173 38 38]]
   [:back [235 228 32 29]]
   [:guide [287 195 69 69]]
   [:start [377 228 32 29]]
   [:left-stick [91 234 63 63]]
   [:right-stick [381 354 63 63]]
   [:left-shoulder [74 73 102 29]]
   [:right-shoulder [468 73 102 29]]
   [:dpad-up [207 316 32 32]]
   [:dpad-down [207 384 32 32]]
   [:dpad-left [173 351 32 32]]
   [:dpad-right [242 351 32 32]]
   [:misc1 [310 286 23 27]]])
   ;; there are other buttons: paddles on the back of the gamepad, touchpads, etc, but this is good enough for now.

(defn init [state _]
  (sdl/set-app-metadata! "Example Input Gamepad Polling" "1.0" "com.example.input-gamepad-polling")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :gamepad)
    (let [[window renderer] (r/create-window-and-renderer "examples/input/gamepad-polling" window-width window-height :resizable)]
      (r/set-logical-presentation! renderer window-width window-height :stretch)
      ;; Textures are pixel data that we upload to the video hardware for fast drawing. Lots of 2D
      ;; engines refer to these as "sprites." We'll do a static texture (upload once, draw many
      ;; times) with data from a bitmap file.

      ;; SDL_Surface is pixel data the CPU can access. SDL_Texture is pixel data the GPU can access.
      ;; Load a .png into a surface, move it to a texture from there.
      (let [surface (surface/load-png (assets/path "gamepad_front.png"))
            texture (r/create-texture-from-surface renderer surface)]
        (surface/destroy-surface! surface) ; done with this, the texture has a copy of the pixels now.
        (swap! state assoc :window window :renderer renderer :texture texture :gamepad nil
               :leftthumblast 0xFFFFFFFF :rightthumblast 0xFFFFFFFF))
      :continue)))

(defn event [state e]
  (case (:type e)
    :quit :success
    ;; this event is sent for each hotplugged gamepad, but also each already-connected gamepad during SDL_Init().
    :gamepad-added
    (do (when-not (:gamepad @state) ; we don't have a stick yet and one was added, open it!
          (try (swap! state assoc :gamepad (gp/open (:which e)))
               (catch clojure.lang.ExceptionInfo ex
                 (log/log! "Failed to open gamepad ID " (:which e) ": " (:sdl/error (ex-data ex))))))
        :continue)
    :gamepad-removed
    (let [g (:gamepad @state)]
      (when (and g (= (gp/id g) (:which e)))
        (gp/close! g) ; our controller was unplugged.
        (swap! state assoc :gamepad nil))
      :continue)
    :continue))

(defn- thumb!
  "Draw a thumbstick's box if it moved in the last half-second; `last-key` in state
  remembers when it last moved."
  [state renderer gamepad now axis-x axis-y last-key cx cy]
  (let [x (gp/axis gamepad axis-x)
        y (gp/axis gamepad axis-y)]
    (when (or (> (Math/abs x) 1000) (> (Math/abs y) 1000)) ; zero means centered, but it might be a little off zero...
      (swap! state assoc last-key now))                     ; keep drawing, we're still moving.
    (when (< (- now (get @state last-key)) 500)             ; draw if there was movement in the last half-second.
      (r/fill-rect! renderer (+ cx (* (/ x 32767.0) 30.0)) (+ cy (* (/ y 32767.0) 30.0)) 30 30))))

(defn- trigger! [renderer gamepad axis x]
  (let [v (gp/axis gamepad axis)]
    (when (> v 1000) ; zero means unpressed, but it might be a little off zero...
      (let [height (* (/ v 32767.0) 65.0)]
        (r/fill-rect! renderer x (+ 1 (- 65.0 height)) 37 height)))))

(defn iterate [state]
  (let [{:keys [renderer texture gamepad]} @state
        now (timer/ticks)
        text (if gamepad (gp/gamepad-name gamepad) "Plug in a gamepad, please.")]
    (r/set-draw-color! renderer 0xFF 0xFF 0xFF 0xFF) ; white
    (r/clear! renderer)
    ;; note that you can get input as events, instead of polling, which is
    ;; better since it won't miss button presses if the system is lagging,
    ;; but often times checking the current state per-frame is good enough,
    ;; and maybe better if you'd rather _drop_ inputs due to lag.
    (when gamepad
      (r/render-texture! renderer texture) ; draw the gamepad picture to the whole window.
      ;; draw green boxes over buttons that are currently pressed.
      (r/set-draw-color! renderer 0x00 0xFF 0x00 0xFF) ; green
      (doseq [[b rect] buttons]
        (when (gp/button? gamepad b)
          (r/fill-rect! renderer rect)))
      ;; draw axes in blue.
      (r/set-draw-color! renderer 0x00 0x00 0xFF 0xFF) ; blue
      (thumb! state renderer gamepad now :leftx :lefty :leftthumblast 107 252)     ; left thumb axis.
      (thumb! state renderer gamepad now :rightx :righty :rightthumblast 397 370)  ; right thumb axis.
      (trigger! renderer gamepad :left-trigger 127)    ; left trigger.
      (trigger! renderer gamepad :right-trigger 481))  ; right trigger.
    (let [x (/ (- window-width (* (count text) char-size)) 2.0)
          y (if gamepad
              (double (- window-height (+ char-size 2)))
              (/ (- window-height char-size) 2.0))]
      (r/set-draw-color! renderer 0x00 0x00 0xFF 0xFF) ; blue
      (r/debug-text! renderer x y text))
    (r/present! renderer)
    :continue))

(defn quit [state _]
  (let [{:keys [texture gamepad]} @state]
    (when texture (r/destroy-texture! texture))
    (when gamepad (gp/close! gamepad))))
  ;; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
