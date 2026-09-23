(ns examples.sdl.input.gamepad-events
  "Port of SDL's examples/input/04-gamepad-events: look for gamepad input in the
  event handler, and report any changes as a flood of info.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.gamepad :as gp]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [sdl3.video :as video]
            [examples.sdl.app :as app]))

(def ^:private char-size 8)            ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE
(def ^:private motion-event-cooldown 40)

(defn- battery-state-string [st]
  (case st
    :error "ERROR" :unknown "UNKNOWN" :on-battery "ON BATTERY" :no-battery "NO BATTERY"
    :charging "CHARGING" :charged "CHARGED" "UNKNOWN"))

(defn- add-message!
  "Queue a message, colored by the gamepad it came from."
  [state jid & parts]
  (swap! state (fn [{:keys [colors] :as s}]
                 (update s :messages conj {:str (apply str parts)
                                           :color (nth colors (mod jid (count colors)))
                                           :start-ticks (timer/ticks)}))))

(defn init [state _]
  (sdl/set-app-metadata! "Example Input Gamepad Events" "1.0" "com.example.input-gamepad-events")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :gamepad)
    (let [[window renderer] (r/create-window-and-renderer "examples/input/gamepad-events" 640 480 :resizable)]
      (swap! state assoc :window window :renderer renderer :messages [] :axis-cooldown 0
             :colors (into [[255 255 255 255]]
                           (repeatedly 63 (fn [] [(rand-int 255) (rand-int 255) (rand-int 255) 255]))))
      (add-message! state 0 "Please plug in a gamepad.")
      :continue)))

(defn event [state e]
  (let [which (:which e)]
    (case (:type e)
      :quit nil
      ;; this event is sent for each hotplugged stick, but also each already-connected gamepad during SDL_Init().
      :gamepad-added
      (try (let [g (gp/open which)
                 mapping (gp/mapping g)]
             (add-message! state which "Gamepad #" which " ('" (gp/gamepad-name g) "') added")
             (when mapping
               (add-message! state which "Gamepad #" which " mapping: " mapping)))
           (catch clojure.lang.ExceptionInfo ex
             (add-message! state which "Gamepad #" which " add, but not opened: " (:sdl/error (ex-data ex)))))
      :gamepad-removed
      (do (when-let [g (gp/from-id which)]
            (gp/close! g)) ; the gamepad was unplugged.
          (add-message! state which "Gamepad #" which " removed"))
      :gamepad-axis-motion
      (let [now (timer/ticks)] ; these are spammy, only show every X milliseconds.
        (when (>= now (:axis-cooldown @state))
          (swap! state assoc :axis-cooldown (+ now motion-event-cooldown))
          (add-message! state which "Gamepad #" which " axis " (gp/string-for-axis (:axis e)) " -> " (:value e))))
      (:gamepad-button-up :gamepad-button-down)
      (add-message! state which "Gamepad #" which " button " (gp/string-for-button (:button e)) " -> "
                    (if (:down e) "PRESSED" "RELEASED"))
      :joystick-battery-updated
      ;; this is only reported for joysticks, so make sure this joystick is _actually_ a gamepad.
      (when (gp/gamepad? which)
        (add-message! state which "Gamepad #" which " battery -> " (battery-state-string (:state e)) " - " (:percent e) "%"))
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

;; SDL will clean up the window/renderer for us. We let the gamepads leak.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
