(ns examples.sdl.misc.clipboard
  "Port of SDL's examples/misc/02-clipboard: let the user copy and paste with the
  system clipboard.

  This only handles text, but SDL supports other data types, too.

  The original C is public domain; so is this port."
  (:require [clojure.string :as str]
            [sdl3.core :as sdl]
            [sdl3.clipboard :as clipboard]
            [sdl3.rect :as rect]
            [sdl3.render :as r]
            [sdl3.time :as time]
            [examples.sdl.app :as app]))

(def ^:private char-size 8) ; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE
(def ^:private copybuttonstr "Click here to copy!")
(def ^:private pastebuttonstr "Click here to paste!")

(def ^:private months ["January" "February" "March" "April" "May" "June" "July" "August" "September" "October" "November" "December"])
(def ^:private days ["Sunday" "Monday" "Tuesday" "Wednesday" "Thursday" "Friday" "Saturday"])

(defn- current-time-string []
  (try
    (let [dt (time/local-now)]
      (format "%s, %s %d, %d   %02d:%02d:%02d" (days (:day-of-week dt)) (months (dec (:month dt)))
              (:day dt) (:year dt) (:hour dt) (:minute dt) (:second dt)))
    (catch clojure.lang.ExceptionInfo _
      "(Don't know the current time, sorry.)")))

;; set up the locations where we'll draw stuff.
(def ^:private currenttimerect {:x 30 :y 10 :w 390 :h (+ char-size 10)})
(def ^:private copybuttonrect {:x (+ 30 390 30) :y 10
                               :w (+ (* char-size (count copybuttonstr)) 10) :h (:h currenttimerect)})
(def ^:private pastetextrect (let [y (+ 10 (:h currenttimerect) 10)]
                               {:x 10 :y y :w 620 :h (- (- 480 y) (:h copybuttonrect) 20)}))
(def ^:private pastebuttonrect (let [w (+ (* char-size (count pastebuttonstr)) 10)]
                                 {:w w :x (/ (- 640 w) 2.0)
                                  :y (+ (:y pastetextrect) (:h pastetextrect) 10) :h (:h copybuttonrect)}))

(defn init [state _]
  (sdl/set-app-metadata! "Example Misc Clipboard" "1.0" "com.example.misc-clipboard")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/misc/clipboard" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      (swap! state assoc :window window :renderer renderer :current-time (current-time-string)
             :copy-pressed false :paste-pressed false :pasted-str nil)
      :continue)))

(defn event [state e]
  ;; SDL_ConvertEventToRenderCoordinates: mouse events arrive in window coordinates,
  ;; and the buttons are laid out in the 640x480 logical coordinates.
  (let [{:keys [renderer]} @state
        p (when (#{:mouse-button-down :mouse-button-up} (:type e))
            (r/coordinates-from-window renderer (:x e) (:y e)))]
    (case (:type e)
      :quit :success
      :mouse-button-down
      (do (when (= :left (:button e))
            (swap! state assoc
                   :copy-pressed (rect/point-in-rect? p copybuttonrect)
                   :paste-pressed (rect/point-in-rect? p pastebuttonrect)))
          :continue)
      :mouse-button-up
      (do (when (= :left (:button e))
            (let [{:keys [copy-pressed paste-pressed current-time]} @state]
              (cond
                (and copy-pressed (rect/point-in-rect? p copybuttonrect)) (clipboard/set-text! current-time)
                (and paste-pressed (rect/point-in-rect? p pastebuttonrect)) (swap! state assoc :pasted-str (clipboard/text))))
            (swap! state assoc :copy-pressed false :paste-pressed false))
          :continue)
      :continue)))

(defn- render-pasted-text! [renderer s]
  (when s
    (let [x (+ (:x pastetextrect) 5)
          w (- (:w pastetextrect) 10)
          h (:h pastetextrect)
          max-chars-per-line (long (/ w char-size))]
      ;; this doesn't wordwrap, or deal with Unicode....this is just a simple example app!
      (loop [[line & more] (str/split-lines s)
             y (+ (:y pastetextrect) 5)]
        (when (and line (>= (- h y) char-size))
          (r/debug-text! renderer x y (subs line 0 (min (count line) max-chars-per-line)))
          (recur more (+ y char-size 2)))))))

(defn iterate [state]
  (swap! state assoc :current-time (current-time-string))
  (let [{:keys [renderer current-time copy-pressed paste-pressed pasted-str]} @state]
    (r/set-draw-color! renderer 0 0 0 255) ; black
    (r/clear! renderer)
    ;; draw a frame around the current time.
    (r/set-draw-color! renderer 0 0 255 255)
    (r/fill-rect! renderer currenttimerect)
    (r/set-draw-color! renderer 255 255 255 255)
    (r/draw-rect! renderer currenttimerect)
    ;; draw the current time inside the frame.
    (r/set-draw-color! renderer 255 255 0 255)
    (r/debug-text! renderer (+ (:x currenttimerect) (/ (- (:w currenttimerect) (* char-size (count current-time))) 2.0))
                   (+ (:y currenttimerect) 5) current-time)
    ;; draw a frame for the "copy the current time to the clipboard" button.
    (if copy-pressed (r/set-draw-color! renderer 0 255 0 255) (r/set-draw-color! renderer 255 0 0 255))
    (r/fill-rect! renderer copybuttonrect)
    (r/set-draw-color! renderer 255 255 255 255)
    (r/draw-rect! renderer copybuttonrect)
    ;; draw the "copy this text" button string.
    (r/debug-text! renderer (+ (:x copybuttonrect) 5) (+ (:y copybuttonrect) 5) copybuttonstr)
    ;; draw a frame for the pasted text area.
    (r/set-draw-color! renderer 0 53 25 255)
    (r/fill-rect! renderer pastetextrect)
    (r/set-draw-color! renderer 255 255 255 255)
    (r/draw-rect! renderer pastetextrect)
    ;; draw pasted text.
    (r/set-draw-color! renderer 0 219 107 255)
    (render-pasted-text! renderer pasted-str)
    ;; draw a frame for the "paste from the clipboard" button.
    (if paste-pressed (r/set-draw-color! renderer 0 255 0 255) (r/set-draw-color! renderer 255 0 0 255))
    (r/fill-rect! renderer pastebuttonrect)
    (r/set-draw-color! renderer 255 255 255 255)
    (r/draw-rect! renderer pastebuttonrect)
    ;; draw the "paste some text" button string.
    (r/debug-text! renderer (+ (:x pastebuttonrect) 5) (+ (:y pastebuttonrect) 5) pastebuttonstr)
    ;; put the new rendering on the screen.
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
