(ns examples.sdl.demo.infinite-monkeys
  "Port of SDL's examples/demo/03-infinite-monkeys: a hundred monkeys hammer
  random keys until, between them, they have typed out a text — Jabberwocky by
  default, or any UTF-8 file you pass:

      jolt sdl-example demo/infinite-monkeys [--monkeys N] [file.txt]

  The text is kept as a vector of Unicode code points, which is how the C steps
  through it with SDL_StepUTF8; the progress bar measures code points where the C
  measures bytes.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.io :as io]
            [sdl3.keyboard :as kb]
            [sdl3.render :as r]
            [sdl3.time :as time]
            [examples.sdl.app :as app]))

;; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE: the debug font is 8x8 pixels
(def char-size 8)

;; The highest and lowest scancodes a monkey can hit
(def min-monkey-scancode (kb/scancode :a))
(def max-monkey-scancode (kb/scancode :slash))

(def default-text
  (str "Jabberwocky, by Lewis Carroll\n"
       "\n"
       "'Twas brillig, and the slithy toves\n"
       "      Did gyre and gimble in the wabe:\n"
       "All mimsy were the borogoves,\n"
       "      And the mome raths outgrabe.\n"
       "\n"
       "\"Beware the Jabberwock, my son!\n"
       "      The jaws that bite, the claws that catch!\n"
       "Beware the Jubjub bird, and shun\n"
       "      The frumious Bandersnatch!\"\n"
       "\n"
       "He took his vorpal sword in hand;\n"
       "      Long time the manxome foe he sought-\n"
       "So rested he by the Tumtum tree\n"
       "      And stood awhile in thought.\n"
       "\n"
       "And, as in uffish thought he stood,\n"
       "      The Jabberwock, with eyes of flame,\n"
       "Came whiffling through the tulgey wood,\n"
       "      And burbled as it came!\n"
       "\n"
       "One, two! One, two! And through and through\n"
       "      The vorpal blade went snicker-snack!\n"
       "He left it dead, and with its head\n"
       "      He went galumphing back.\n"
       "\n"
       "\"And hast thou slain the Jabberwock?\n"
       "      Come to my arms, my beamish boy!\n"
       "O frabjous day! Callooh! Callay!\"\n"
       "      He chortled in his joy.\n"
       "\n"
       "'Twas brillig, and the slithy toves\n"
       "      Did gyre and gimble in the wabe:\n"
       "All mimsy were the borogoves,\n"
       "      And the mome raths outgrabe.\n"))

(defn- on-window-size-changed [state]
  (let [{:keys [renderer]} @state
        [w h] (r/current-output-size renderer)
        rows (- (quot h char-size) 4)
        cols (quot w char-size)]
    (swap! state assoc
           :row 0 :rows rows :cols cols
           :lines (when (and (pos? rows) (pos? cols)) (vec (repeat rows [])))
           :monkey-chars (when (pos? cols) (vec (repeat cols (int \space)))))))

(defn init [state args]
  (sdl/set-app-metadata! "Infinite Monkeys" "1.0" "com.example.infinite-monkeys")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/demo/infinite-monkeys" 640 480 nil)]
      (r/set-vsync! renderer 1)
      (let [[monkeys args] (if (= "--monkeys" (first args))
                             (if (second args)
                               [(parse-long (second args)) (drop 2 args)]
                               [nil nil])
                             [100 args])]
        (if-not monkeys
          (do (println "Usage: infinite-monkeys [--monkeys N] [file.txt]") :failure)
          (let [text (if-let [file (first args)]
                       (String. ^bytes (io/load-file file) "UTF-8")
                       default-text)]
            (reset! state {:window window :renderer renderer
                           :monkeys monkeys
                           ;; code points, as SDL_StepUTF8 would produce them
                           :text (mapv int (seq text))
                           :progress 0
                           :start-time (time/now)
                           :end-time nil})
            (on-window-size-changed state)
            :continue))))))

(defn event [state e]
  (case (:type e)
    :window-pixel-size-changed (do (on-window-size-changed state) :continue)
    :quit :success
    :continue))

(defn- display-line [renderer x y line]
  (r/debug-text! renderer x y (apply str (map char line))))

(defn- can-monkey-type? [ch]
  (let [[scancode modstate] (kb/scancode-from-key ch)
        sc (kb/scancode scancode)]
    (and (<= min-monkey-scancode sc max-monkey-scancode)
         ;; Monkeys can hit the shift key, but nothing else
         (every? #{:lshift :rshift} modstate))))

(defn- advance-row [s]
  (let [row (inc (:row s))]
    (-> s (assoc :row row) (assoc-in [:lines (mod row (:rows s))] []))))

(defn- add-monkey-char [s monkey ch]
  (let [s (if (and (>= monkey 0) (:monkey-chars s))
            (assoc-in s [:monkey-chars (mod monkey (:cols s))] ch)
            s)
        s (if-not (:lines s)
            s
            (if (= ch (int \newline))
              (advance-row s)
              (let [i (mod (:row s) (:rows s))
                    s (update-in s [:lines i] conj ch)]
                (if (= (count (get-in s [:lines i])) (:cols s))
                  (advance-row s)
                  s))))]
    (update s :progress inc)))

(defn- get-next-char
  "The next character a monkey has to type, handing out the ones monkeys can't
  type for free. Answers [state ch], ch 0 when the text is done."
  [s]
  (loop [s s]
    (if (>= (:progress s) (count (:text s)))
      [s 0]
      (let [ch (nth (:text s) (:progress s))]
        (if (can-monkey-type? ch)
          [s ch]
          ;; This is a freebie, monkeys can't type this
          (recur (add-monkey-char s -1 ch)))))))

(defn- monkey-play []
  (let [count (inc (- max-monkey-scancode min-monkey-scancode))
        scancode (+ min-monkey-scancode (rand-int count))
        modstate (if (zero? (rand-int 2)) nil :shift)]
    (kb/keycode (kb/key-from-scancode scancode (when modstate [:lshift :rshift]) false))))

(defn iterate [state]
  (swap! state
         (fn [s]
           (loop [s s monkey 0 next-char 0]
             (if (>= monkey (:monkeys s))
               s
               (let [[s next-char] (if (zero? next-char) (get-next-char s) [s next-char])]
                 (if (zero? next-char)
                   s                                     ; All done!
                   (let [ch (monkey-play)]
                     (if (= ch next-char)
                       (recur (add-monkey-char s monkey ch) (inc monkey) 0)
                       (recur s (inc monkey) next-char)))))))))
  (let [{:keys [renderer lines row rows cols monkey-chars monkeys text progress start-time end-time]} @state
        x 0.0]
    ;; Clear the screen
    (r/set-draw-color! renderer 0 0 0 255)
    (r/clear! renderer)

    ;; Show the text already decoded
    (r/set-draw-color! renderer 255 255 255 255)
    (let [y (if-not lines
              0.0
              (let [row-offset (max 0 (inc (- row rows)))]
                (doseq [i (range rows)]
                  (display-line renderer x (* i char-size) (nth lines (mod (+ row-offset i) rows))))
                ;; Show the caption
                (let [y (double (* (inc rows) char-size))
                      done? (= progress (count text))
                      _ (when (and done? (not end-time)) (swap! state assoc :end-time (time/now)))
                      now (if done? (:end-time @state) (time/now))
                      elapsed (quot (- now start-time) 1000000000)
                      seconds (mod elapsed 60)
                      minutes (mod (quot elapsed 60) 60)
                      hours (quot elapsed 3600)]
                  (r/debug-text! renderer x y (str "Monkeys: " monkeys " - " hours "H:" minutes "M:" seconds "S"))
                  ;; Show the characters currently typed
                  (display-line renderer x (+ y char-size) monkey-chars)
                  (+ y (* 2 char-size)))))]
      ;; Show the current progress
      (r/set-draw-color! renderer 0 255 0 255)
      (r/fill-rect! renderer {:x x :y y
                              :w (* (/ (double progress) (count text)) (* cols char-size))
                              :h char-size}))
    (r/present! renderer)
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
