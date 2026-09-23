(ns examples.sdl.misc.power
  "Port of SDL's examples/misc/01-power: report power status (plugged in,
  battery level, etc).

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [sdl3.system :as system]
            [examples.sdl.app :as app]))

(def ^:private char-size 8) ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE

(defn init [state _]
  (sdl/set-app-metadata! "Example Misc Power" "1.0" "com.example.misc-power")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/misc/power" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      (swap! state assoc :window window :renderer renderer)
      :continue)))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn- power-info
  "Query for battery info. sdl3.system/power-info throws when SDL reports
  SDL_POWERSTATE_ERROR; this turns that back into a state, as the C example sees it."
  []
  (try (system/power-info)
       (catch clojure.lang.ExceptionInfo e
         {:state :error :error (:sdl/error (ex-data e))})))

(defn iterate [state]
  (let [{:keys [renderer]} @state
        frame {:x 100 :y 200 :w 440 :h 80} ; the percentage bar dimensions.
        {power-state :state :keys [seconds percent error]} (power-info)
        ;; We set up different drawing details for each power state, then
        ;; run it all through the same drawing code.
        {:keys [clear bar msg msg2]}
        (merge {:clear [0 0 0] :bar [0 0 0]}  ; clear window to this color / draw a percentage bar in this color.
               (case power-state
                 :error {:msg2 "ERROR GETTING POWER STATE" :msg error :clear [255 0 0]} ; red background
                 :on-battery {:msg "Running on battery." :bar [255 0 0]}                ; draw in red
                 :no-battery {:msg "Plugged in, no battery available." :clear [0 50 0]} ; green background
                 :charging {:msg "Charging." :bar [0 255 255]}                          ; draw in cyan
                 :charged {:msg "Charged." :bar [0 255 0]}                              ; draw in green
                 ;; in case this does something unexpected later, treat it as unknown.
                 {:msg "Power state is unknown." :clear [50 50 50]}))                   ; grey background
        text [255 255 255]  ; draw messages in this color.
        framec [255 255 255]] ; draw a percentage bar frame in this color.
    (r/set-draw-color! renderer (conj clear 255))
    (r/clear! renderer)
    (when percent
      (let [remain (if-not seconds
                     "unknown time"
                     (format "%02d:%02d:%02d" (quot seconds 3600) (quot (mod seconds 3600) 60) (mod seconds 60)))
            msgbuf (format "Battery: %3d percent, %s remaining" percent remain)
            x (+ (:x frame) (/ (- (:w frame) (* char-size (count msgbuf))) 2.0))
            y (+ (:y frame) (:h frame) char-size)]
        (r/set-draw-color! renderer (conj bar 255)) ; draw percent bar.
        (r/fill-rect! renderer (update frame :w * (/ percent 100.0)))
        (r/set-draw-color! renderer (conj framec 255)) ; draw frame on top of bar.
        (r/draw-rect! renderer frame)
        (r/set-draw-color! renderer (conj text 255))
        (r/debug-text! renderer x y msgbuf))) ; draw text about battery level
    (when msg
      (r/set-draw-color! renderer (conj text 255))
      (r/debug-text! renderer (+ (:x frame) (/ (- (:w frame) (* char-size (count msg))) 2.0))
                     (- (:y frame) (* char-size 2)) msg))
    (when msg2
      (r/set-draw-color! renderer (conj text 255))
      (r/debug-text! renderer (+ (:x frame) (/ (- (:w frame) (* char-size (count msg2))) 2.0))
                     (- (:y frame) (* char-size 4)) msg2))
    ;; put the new rendering on the screen.
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
