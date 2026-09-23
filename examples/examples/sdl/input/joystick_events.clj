(ns examples.sdl.input.joystick-events
  "Port of SDL's examples/input/02-joystick-events: look for joystick input in
  the event handler, and report any changes as a flood of info.

  The original C is public domain; so is this port."
  (:require [clojure.string :as str]
            [sdl3.core :as sdl]
            [sdl3.joystick :as js]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
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

(def ^:private char-size 8)            ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE
(def ^:private motion-event-cooldown 40)

(def ^:private hat-state-strings
  {:centered "CENTERED" :up "UP" :right "RIGHT" :down "DOWN" :left "LEFT"
   :rightup "RIGHT+UP" :rightdown "RIGHT+DOWN" :leftup "LEFT+UP" :leftdown "LEFT+DOWN"})

(defn- battery-state-string [st]
  (case st
    :error "ERROR" :unknown "UNKNOWN" :on-battery "ON BATTERY" :no-battery "NO BATTERY"
    :charging "CHARGING" :charged "CHARGED" "UNKNOWN"))

(defn- add-message!
  "Queue a message, colored by the joystick it came from."
  [state jid & parts]
  (swap! state (fn [{:keys [colors] :as s}]
                 (update s :messages conj {:str (apply str parts)
                                           :color (nth colors (mod jid (count colors)))
                                           :start-ticks (timer/ticks)}))))

(defn init [state _]
  (sdl/set-app-metadata! "Example Input Joystick Events" "1.0" "com.example.input-joystick-events")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :joystick)
    (let [[window renderer] (r/create-window-and-renderer "examples/input/joystick-events" 640 480 :resizable)]
      (swap! state assoc :window window :renderer renderer :messages []
             :axis-cooldown 0 :ball-cooldown 0
             :colors (into [[255 255 255 255]]
                           (repeatedly 63 (fn [] [(rand-int 255) (rand-int 255) (rand-int 255) 255]))))
      (add-message! state 0 "Please plug in a joystick.")
      :continue)))

(defn event [state e]
  (let [which (:which e)]
    (case (:type e)
      :quit :success
      ;; this event is sent for each hotplugged stick, but also each already-connected joystick during SDL_Init().
      :joystick-added
      (try (let [j (js/open which)]
             (add-message! state which "Joystick #" which " ('" (js/joystick-name j) "') added"))
           (catch clojure.lang.ExceptionInfo ex
             (add-message! state which "Joystick #" which " add, but not opened: " (:sdl/error (ex-data ex)))))
      :joystick-removed
      (do (when-let [j (js/from-id which)]
            (js/close! j)) ; the joystick was unplugged.
          (add-message! state which "Joystick #" which " removed"))
      :joystick-axis-motion
      (let [now (timer/ticks)] ; these are spammy, only show every X milliseconds.
        (when (>= now (:axis-cooldown @state))
          (swap! state assoc :axis-cooldown (+ now motion-event-cooldown))
          (add-message! state which "Joystick #" which " axis " (:axis e) " -> " (:value e))))
      :joystick-ball-motion
      (let [now (timer/ticks)] ; these are spammy, only show every X milliseconds.
        (when (>= now (:ball-cooldown @state))
          (swap! state assoc :ball-cooldown (+ now motion-event-cooldown))
          (add-message! state which "Joystick #" which " ball " (:ball e) " -> " (:xrel e) ", " (:yrel e))))
      :joystick-hat-motion
      (add-message! state which "Joystick #" which " hat " (:hat e) " -> " (get hat-state-strings (:value e) "UNKNOWN"))
      (:joystick-button-up :joystick-button-down)
      (add-message! state which "Joystick #" which " button " (:button e) " -> " (if (:down e) "PRESSED" "RELEASED"))
      :joystick-battery-updated
      (add-message! state which "Joystick #" which " battery -> " (battery-state-string (:state e)) " - " (:percent e) "%")
      nil)
    (if (= :quit (:type e)) :success :continue)))

(defn iterate [state]
  (let [{:keys [window renderer]} @state
        now (timer/ticks)
        msg-lifetime 3500.0 ; milliseconds a message lives for.
        _ (do (r/set-draw-color! renderer 0 0 0 255)
              (r/clear! renderer))
        [winw winh] (video/window-size window)
        ;; walk the messages oldest first: finished ones drop off the front, and
        ;; a message that would overlap the one before it waits its turn.
        messages (loop [[msg & more :as msgs] (:messages @state) prev-y 0.0 kept []]
                   (if-not msg
                     kept
                     (let [life-percent (/ (- now (:start-ticks msg)) msg-lifetime)]
                       (if (>= life-percent 1.0) ; msg is done.
                         (recur more prev-y kept)
                         (let [x (/ (- winw (* (count (:str msg)) char-size)) 2.0)
                               y (* winh life-percent)]
                           (if (and (not= prev-y 0.0) (< (- prev-y y) char-size))
                             ;; wait for the previous message to tick up a little.
                             (into (conj kept (assoc msg :start-ticks now)) more)
                             (let [[cr cg cb ca] (:color msg)]
                               (r/set-draw-color! renderer cr cg cb (int (* ca (- 1.0 life-percent))))
                               (r/debug-text! renderer x y (:str msg))
                               (recur more y (conj kept msg)))))))))]
    (swap! state assoc :messages messages)
    (r/present! renderer)
    :continue))

;; SDL will clean up the window/renderer for us. We let the joysticks leak.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
