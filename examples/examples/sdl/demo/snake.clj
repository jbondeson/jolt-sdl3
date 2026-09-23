(ns examples.sdl.demo.snake
  "Port of SDL's examples/demo/01-snake: the Snake game. Arrow keys (or a
  joystick hat) steer, R restarts, Escape or Q quits.

  The C packs each board cell into 3 bits to keep the whole game state small;
  this port keeps the same cell values in a plain vector, which is the natural
  Clojure representation, and the game logic is otherwise the same.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.joystick :as joystick]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [sdl3.video :as video]
            [examples.sdl.app :as app]))

(def step-rate-in-milliseconds 125)
(def snake-block-size-in-pixels 24)
(def snake-game-width 24)
(def snake-game-height 18)
(def sdl-window-width (* snake-block-size-in-pixels snake-game-width))
(def sdl-window-height (* snake-block-size-in-pixels snake-game-height))

;; cell contents: nothing, a body segment heading right/up/left/down, or food
(def cell-nothing 0)
(def cell-sright 1)
(def cell-sup 2)
(def cell-sleft 3)
(def cell-sdown 4)
(def cell-food 5)

(def dir-right 0)
(def dir-up 1)
(def dir-left 2)
(def dir-down 3)

(defn- index [x y] (+ x (* y snake-game-width)))

(defn snake-cell-at [ctx x y]
  (nth (:cells ctx) (index x y)))

(defn- put-cell-at [ctx x y ct]
  (assoc-in ctx [:cells (index x y)] ct))

(defn- are-cells-full? [ctx]
  (= (:occupied-cells ctx) (* snake-game-width snake-game-height)))

(defn- new-food-pos [ctx]
  (loop []
    (let [x (rand-int snake-game-width)
          y (rand-int snake-game-height)]
      (if (= cell-nothing (snake-cell-at ctx x y))
        (put-cell-at ctx x y cell-food)
        (recur)))))

(defn snake-initialize []
  (let [cx (quot snake-game-width 2)
        cy (quot snake-game-height 2)
        ctx {:cells (vec (repeat (* snake-game-width snake-game-height) cell-nothing))
             :head-xpos cx :head-ypos cy
             :tail-xpos cx :tail-ypos cy
             :next-dir dir-right
             :inhibit-tail-step 4
             :occupied-cells 3}
        ctx (put-cell-at ctx cx cy cell-sright)]
    (reduce (fn [ctx _] (-> ctx new-food-pos (update :occupied-cells inc)))
            ctx (range 4))))

(defn snake-redir [ctx dir]
  (let [ct (snake-cell-at ctx (:head-xpos ctx) (:head-ypos ctx))]
    (if (or (and (= dir dir-right) (not= ct cell-sleft))
            (and (= dir dir-up) (not= ct cell-sdown))
            (and (= dir dir-left) (not= ct cell-sright))
            (and (= dir dir-down) (not= ct cell-sup)))
      (assoc ctx :next-dir dir)
      ctx)))

(defn- wrap-around [v max]
  (cond (neg? v) (dec max)
        (> v (dec max)) 0
        :else v))

(defn snake-step [ctx]
  (let [dir-as-cell (inc (:next-dir ctx))
        ;; move tail forward
        ctx (let [ctx (update ctx :inhibit-tail-step dec)]
              (if-not (zero? (:inhibit-tail-step ctx))
                ctx
                (let [{:keys [tail-xpos tail-ypos]} ctx
                      ct (snake-cell-at ctx tail-xpos tail-ypos)
                      [tx ty] (condp = ct
                                cell-sright [(inc tail-xpos) tail-ypos]
                                cell-sup [tail-xpos (dec tail-ypos)]
                                cell-sleft [(dec tail-xpos) tail-ypos]
                                cell-sdown [tail-xpos (inc tail-ypos)]
                                [tail-xpos tail-ypos])]
                  (-> ctx
                      (update :inhibit-tail-step inc)
                      (put-cell-at tail-xpos tail-ypos cell-nothing)
                      (assoc :tail-xpos (wrap-around tx snake-game-width)
                             :tail-ypos (wrap-around ty snake-game-height))))))
        ;; move head forward
        prev-xpos (:head-xpos ctx)
        prev-ypos (:head-ypos ctx)
        [hx hy] (condp = (:next-dir ctx)
                  dir-right [(inc prev-xpos) prev-ypos]
                  dir-up [prev-xpos (dec prev-ypos)]
                  dir-left [(dec prev-xpos) prev-ypos]
                  dir-down [prev-xpos (inc prev-ypos)])
        hx (wrap-around hx snake-game-width)
        hy (wrap-around hy snake-game-height)
        ctx (assoc ctx :head-xpos hx :head-ypos hy)
        ;; collisions
        ct (snake-cell-at ctx hx hy)]
    (if (and (not= ct cell-nothing) (not= ct cell-food))
      (snake-initialize)
      (let [ctx (-> ctx
                    (put-cell-at prev-xpos prev-ypos dir-as-cell)
                    (put-cell-at hx hy dir-as-cell))]
        (cond
          (not= ct cell-food) ctx
          (are-cells-full? ctx) (snake-initialize)
          :else (-> ctx new-food-pos (update :inhibit-tail-step inc) (update :occupied-cells inc)))))))

(defn- handle-key-event [state scancode]
  (case scancode
    ;; quit
    (:escape :q) :success
    ;; restart the game as if the program was launched
    :r (do (swap! state assoc :snake-ctx (snake-initialize)) :continue)
    ;; decide new direction of the snake
    :right (do (swap! state update :snake-ctx snake-redir dir-right) :continue)
    :up (do (swap! state update :snake-ctx snake-redir dir-up) :continue)
    :left (do (swap! state update :snake-ctx snake-redir dir-left) :continue)
    :down (do (swap! state update :snake-ctx snake-redir dir-down) :continue)
    :continue))

(defn- handle-hat-event [state hat]
  (case hat
    :right (swap! state update :snake-ctx snake-redir dir-right)
    :up (swap! state update :snake-ctx snake-redir dir-up)
    :left (swap! state update :snake-ctx snake-redir dir-left)
    :down (swap! state update :snake-ctx snake-redir dir-down)
    nil)
  :continue)

(defn iterate [state]
  (let [now (timer/ticks)]
    ;; run game logic if we're at or past the time to run it.
    ;; if we're _really_ behind the time to run it, run it
    ;; several times.
    (loop []
      (when (>= (- now (:last-step @state)) step-rate-in-milliseconds)
        (swap! state (fn [s] (-> s
                                 (update :snake-ctx snake-step)
                                 (update :last-step + step-rate-in-milliseconds))))
        (recur))))
  (let [{:keys [renderer snake-ctx]} @state
        size snake-block-size-in-pixels]
    (r/set-draw-color! renderer 0 0 0 255)
    (r/clear! renderer)
    (doseq [i (range snake-game-width)
            j (range snake-game-height)
            :let [ct (snake-cell-at snake-ctx i j)]
            :when (not= ct cell-nothing)]
      (if (= ct cell-food)
        (r/set-draw-color! renderer 80 80 255 255)
        (r/set-draw-color! renderer 0 128 0 255))   ; body
      (r/fill-rect! renderer (* i size) (* j size) size size))
    (r/set-draw-color! renderer 255 255 0 255)       ; head
    (r/fill-rect! renderer (* (:head-xpos snake-ctx) size) (* (:head-ypos snake-ctx) size) size size)
    (r/present! renderer)
    :continue))

(def ^:private extended-metadata
  {:app-metadata-url-string "https://examples.libsdl.org/SDL3/demo/01-snake/"
   :app-metadata-creator-string "SDL team"
   :app-metadata-copyright-string "Placed in the public domain"
   :app-metadata-type-string "game"})

(defn init [state _]
  (sdl/set-app-metadata! "Example Snake game" "1.0" "com.example.Snake")
  (doseq [[k v] extended-metadata]
    (sdl/set-app-metadata-property! k v))
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :joystick)
    (let [[window renderer] (r/create-window-and-renderer "examples/demo/snake" sdl-window-width sdl-window-height :resizable)]
      (r/set-logical-presentation! renderer sdl-window-width sdl-window-height :letterbox)
      (reset! state {:window window :renderer renderer
                     :snake-ctx (snake-initialize)
                     :last-step (timer/ticks)
                     :joystick nil})
      :continue)))

(defn event [state e]
  (case (:type e)
    :quit :success
    :joystick-added (do (when-not (:joystick @state)
                          (try (swap! state assoc :joystick (joystick/open (:which e)))
                               (catch clojure.lang.ExceptionInfo ex
                                 (app/failure (str "Failed to open joystick ID " (:which e) ": " (ex-message ex))))))
                        :continue)
    :joystick-removed (do (when-let [j (:joystick @state)]
                            (when (= (joystick/id j) (:which e))
                              (joystick/close! j)
                              (swap! state assoc :joystick nil)))
                          :continue)
    :joystick-hat-motion (handle-hat-event state (:value e))
    :key-down (handle-key-event state (:scancode e))
    :continue))

(defn quit [state _]
  (let [{:keys [joystick renderer window]} @state]
    (when joystick (joystick/close! joystick))
    (when renderer (r/destroy-renderer! renderer))
    (when window (video/destroy-window! window))))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
