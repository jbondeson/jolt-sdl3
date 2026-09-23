(ns examples.showcase.bounce
  "Bouncing rects: exercises the renderer, decoded events, keyboard state and
  the frame clock. Space or a click adds a rect, Backspace removes one, F toggles
  fullscreen, Escape quits. `jolt showcase bounce` runs it."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.video :as video]
            [sdl3.render :as r]
            [sdl3.rect :as rect]
            [sdl3.events :as ev]
            [sdl3.keyboard :as kb]
            [sdl3.timer :as timer]))

(def ^:private colors
  [[240 200 60] [80 200 240] [240 90 90] [140 230 120] [220 130 240]])

(defn- new-rect [x y]
  {:x x :y y :w 40 :h 40
   :vx (- (rand 400) 200) :vy (- (rand 400) 200)
   :color (rand-nth colors)})

(defn- step [{:keys [x y w h vx vy] :as b} dt width height]
  (let [nx (+ x (* vx dt)) ny (+ y (* vy dt))
        vx (if (or (< nx 0) (> (+ nx w) width)) (- vx) vx)
        vy (if (or (< ny 0) (> (+ ny h) height)) (- vy) vy)]
    (assoc b :x (max 0 (min nx (- width w))) :y (max 0 (min ny (- height h))) :vx vx :vy vy)))

(defn- handle [state e]
  (case (:type e)
    :quit (assoc state :quit? true)
    :key-down (case (:key e)
                :escape (assoc state :quit? true)
                :space (update state :rects conj (new-rect 300 220))
                :backspace (update state :rects #(vec (butlast %)))
                :f (assoc state :toggle-fullscreen? true)
                state)
    :mouse-button-down (update state :rects conj (new-rect (:x e) (:y e)))
    :window-resized (assoc state :size [(:data1 e) (:data2 e)])
    state))

(defn -main [& _]
  (sdl/with-sdl [:video]
    (let [[win ren] (r/create-window-and-renderer "jolt-sdl3 bounce" 800 600 [:resizable :high-pixel-density])]
      (r/set-vsync! ren true)
      (r/set-logical-presentation! ren 800 600 :letterbox)
      (loop [state {:rects (vec (repeatedly 5 #(new-rect (rand 700) (rand 500)))) :size [800 600]}
             t (timer/seconds)
             frames 0]
        (let [state (reduce handle state (ev/poll-all!))
              now (timer/seconds)
              dt (min 0.05 (- now t))
              [w h] (:size state)
              state (cond-> state
                      (:toggle-fullscreen? state)
                      (-> (dissoc :toggle-fullscreen?)
                          (doto (do (video/set-window-fullscreen! win (not (contains? (video/window-flags win) :fullscreen))))))
                      (kb/key-down? :right) (update :rects #(mapv (fn [b] (update b :vx + 20)) %))
                      true (update :rects #(mapv (fn [b] (step b dt 800 600)) %)))]
          (r/set-draw-color! ren 18 22 40)
          (r/clear! ren)
          (doseq [{:keys [color] :as b} (:rects state)]
            (r/set-draw-color! ren color)
            (r/fill-rect! ren b))
          (r/set-draw-color! ren 200 200 200)
          (r/debug-text! ren 8 8 (str (count (:rects state)) " rects  space/click adds, backspace removes, f fullscreen, esc quits"))
          (r/debug-text! ren 8 20 (str "window " w "x" h "  " (int (/ 1 (max dt 0.0001))) " fps  pressed " (pr-str (kb/pressed-scancodes))))
          (r/present! ren)
          (when-not (:quit? state)
            (recur state now (inc frames)))))
      (r/destroy-renderer! ren)
      (video/destroy-window! win))))
