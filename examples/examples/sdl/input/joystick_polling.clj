(ns examples.sdl.input.joystick-polling
  "Port of SDL's examples/input/01-joystick-polling: look for joystick input in
  the main loop, and draw the state of every axis, button and hat.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.consts :as c]
            [sdl3.joystick :as js]
            [sdl3.log :as log]
            [sdl3.render :as r]
            [sdl3.video :as video]
            [examples.sdl.app :as app]))

;; Joysticks are low-level interfaces: there's something with a bunch of
;; buttons, axes and hats, in no understood order or position. This is
;; a flexible interface, but you'll need to build some sort of configuration
;; UI to let people tell you what button, etc, does what. On top of this
;; interface, SDL offers the "gamepad" API, which works with lots of devices,
;; and knows how to map arbitrary buttons and such to look like an
;; Xbox/PlayStation/etc gamepad. This is easier, and better, for many games,
;; but isn't necessarily a good fit for complex apps and hardware. A flight
;; simulator, a realistic racing game, etc, might want the joystick interface
;; instead of gamepads.

;; SDL can handle multiple joysticks, but for simplicity, this program only
;; deals with the first stick it sees.

(def ^:private char-size 8) ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE

(defn init [state _]
  (sdl/set-app-metadata! "Example Input Joystick Polling" "1.0" "com.example.input-joystick-polling")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :joystick)
    (let [[window renderer] (r/create-window-and-renderer "examples/input/joystick-polling" 640 480 :resizable)]
      (swap! state assoc :window window :renderer renderer :joystick nil
             :colors (vec (repeatedly 64 (fn [] [(rand-int 255) (rand-int 255) (rand-int 255) 255]))))
      :continue)))

(defn event [state e]
  (case (:type e)
    :quit :success
    ;; this event is sent for each hotplugged stick, but also each already-connected joystick during SDL_Init().
    :joystick-added
    (do (when-not (:joystick @state) ; we don't have a stick yet and one was added, open it!
          (try (swap! state assoc :joystick (js/open (:which e)))
               (catch clojure.lang.ExceptionInfo ex
                 (log/log! "Failed to open joystick ID " (:which e) ": " (:sdl/error (ex-data ex))))))
        :continue)
    :joystick-removed
    (let [j (:joystick @state)]
      (when (and j (= (js/id j) (:which e)))
        (js/close! j) ; our joystick was unplugged.
        (swap! state assoc :joystick nil))
      :continue)
    :continue))

(defn- hat-bit? [hat bit]
  (pos? (bit-and (c/joystick-hat hat) (c/joystick-hat bit))))

(defn iterate [state]
  (let [{:keys [window renderer joystick colors]} @state
        text (if joystick (js/joystick-name joystick) "Plug in a joystick, please.")
        _ (do (r/set-draw-color! renderer 0 0 0 255)
              (r/clear! renderer))
        [winw winh] (video/window-size window)]
    ;; note that you can get input as events, instead of polling, which is
    ;; better since it won't miss button presses if the system is lagging,
    ;; but often times checking the current state per-frame is good enough,
    ;; and maybe better if you'd rather _drop_ inputs due to lag.
    (when joystick
      (let [size 30.0
            color #(nth colors (mod % (count colors)))]
        ;; draw axes as bars going across middle of screen. We don't know if it's an X or Y or whatever axis, so we can't do more than this.
        (let [total (js/num-axes joystick)
              x (/ winw 2.0)]
          (doseq [i (range total)]
            (let [val (/ (js/axis joystick i) 32767.0) ; make it -1.0f to 1.0f
                  dx (+ x (* val x))
                  y (+ (/ (- winh (* total size)) 2) (* i size))]
              (r/set-draw-color! renderer (color i))
              (r/fill-rect! renderer {:x dx :y y :w (- x (Math/abs dx)) :h size}))))
        ;; draw buttons as blocks across top of window. We only know the button numbers, but not where they are on the device.
        (let [total (js/num-buttons joystick)
              x0 (/ (- winw (* total size)) 2)]
          (doseq [i (range total)]
            (let [dst {:x (+ x0 (* i size)) :y 0.0 :w size :h size}]
              (if (js/button? joystick i)
                (r/set-draw-color! renderer (color i))
                (r/set-draw-color! renderer 0 0 0 255))
              (r/fill-rect! renderer dst)
              (r/set-draw-color! renderer 255 255 255 (nth (color i) 3))
              (r/draw-rect! renderer dst)))) ; outline it
        ;; draw hats across the bottom of the screen.
        (let [total (js/num-hats joystick)
              x0 (+ (/ (- winw (* total (* size 2.0))) 2.0) (/ size 2.0))
              y (- winh size)
              third (/ size 3.0)]
          (doseq [i (range total)]
            (let [x (+ x0 (* i size 2))
                  hat (js/hat joystick i)]
              (r/set-draw-color! renderer 90 90 90 255)
              (r/fill-rects! renderer [[x (+ y third) size third] [(+ x third) y third size]])
              (r/set-draw-color! renderer (color i))
              (when (hat-bit? hat :up) (r/fill-rect! renderer (+ x third) y third third))
              (when (hat-bit? hat :right) (r/fill-rect! renderer (+ x (* third 2)) (+ y third) third third))
              (when (hat-bit? hat :down) (r/fill-rect! renderer (+ x third) (+ y (* third 2)) third third))
              (when (hat-bit? hat :left) (r/fill-rect! renderer x (+ y third) third third)))))))
    (r/set-draw-color! renderer 255 255 255 255)
    (r/debug-text! renderer (/ (- winw (* (count text) char-size)) 2.0) (/ (- winh char-size) 2.0) text)
    (r/present! renderer)
    :continue))

(defn quit [state _]
  (when-let [j (:joystick @state)]
    (js/close! j)))
  ;; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
