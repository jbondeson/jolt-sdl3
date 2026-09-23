(ns examples.sdl.demo.woodeneye-008
  "Port of SDL's examples/demo/02-woodeneye-008: a split-screen first-person
  shooter for up to four players, each with their own mouse and keyboard. Each
  new mouse or keyboard that sends input joins as the next player (WASD moves,
  Space jumps, the mouse aims and clicks shoot). Escape quits.

  Angles are kept as the C keeps them: yaw an unsigned 32-bit binary angle that
  wraps around, pitch a signed one clamped to straight up and down.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.mouse :as mouse]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [examples.sdl.app :as app]))

(def map-box-scale 16)
(def map-box-edges-len (+ 12 (* map-box-scale 2)))
(def max-player-count 4)
(def circle-draw-sides 32)
(def circle-draw-sides-len (inc circle-draw-sides))

(def ^:private two-32 4294967296)
(def ^:private bin-rad (/ Math/PI 2147483648.0))

(def ^:private extended-metadata
  {:app-metadata-url-string "https://examples.libsdl.org/SDL3/demo/02-woodeneye-008/"
   :app-metadata-creator-string "SDL team"
   :app-metadata-copyright-string "Placed in the public domain"
   :app-metadata-type-string "game"})

(defn- whose-mouse [mouse-id players player-count]
  (first (filter #(= mouse-id (:mouse (nth players %))) (range player-count))))

(defn- whose-keyboard [keyboard-id players player-count]
  (first (filter #(= keyboard-id (:keyboard (nth players %))) (range player-count))))

(defn- random-coordinate []
  (/ (double (* map-box-scale (- (rand-int 256) 128))) 256))

(defn- shoot [shooter players player-count]
  (let [{[x0 y0 z0] :pos :keys [yaw pitch]} (nth players shooter)
        yaw-rad (* bin-rad yaw)
        pitch-rad (* bin-rad pitch)
        cos-yaw (Math/cos yaw-rad)
        sin-yaw (Math/sin yaw-rad)
        cos-pitch (Math/cos pitch-rad)
        sin-pitch (Math/sin pitch-rad)
        vx (* (- sin-yaw) cos-pitch)
        vy sin-pitch
        vz (* (- cos-yaw) cos-pitch)]
    (reduce
     (fn [players i]
       (if (= i shooter)
         players
         (let [{[tx ty tz] :pos r :radius h :height} (nth players i)
               hit (count (for [j [0 1]
                                :let [dx (- tx x0)
                                      dy (+ (- ty y0) (if (zero? j) 0 (- r h)))
                                      dz (- tz z0)
                                      vd (+ (* vx dx) (* vy dy) (* vz dz))
                                      dd (+ (* dx dx) (* dy dy) (* dz dz))
                                      vv (+ (* vx vx) (* vy vy) (* vz vz))
                                      rr (* r r)]
                                :when (and (not (neg? vd)) (>= (* vd vd) (* vv (- dd rr))))]
                            j))]
           (if (pos? hit)
             (assoc-in players [i :pos] [(random-coordinate) (random-coordinate) (random-coordinate)])
             players))))
     players (range player-count))))

(defn- update-player [player dt-ns]
  (let [rate 6.0
        time (* (double dt-ns) 1e-9)
        drag (Math/exp (* (- time) rate))
        diff (- 1.0 drag)
        mult 60.0
        grav 25.0
        rad (* (double (:yaw player)) bin-rad)
        cos (Math/cos rad)
        sin (Math/sin rad)
        wasd (:wasd player)
        bit? (fn [b] (pos? (bit-and wasd b)))
        dir-x (- (if (bit? 8) 1.0 0.0) (if (bit? 2) 1.0 0.0))
        dir-z (- (if (bit? 4) 1.0 0.0) (if (bit? 1) 1.0 0.0))
        norm (+ (* dir-x dir-x) (* dir-z dir-z))
        acc-x (* mult (if (zero? norm) 0.0 (/ (+ (* cos dir-x) (* sin dir-z)) (Math/sqrt norm))))
        acc-z (* mult (if (zero? norm) 0.0 (/ (+ (* (- sin) dir-x) (* cos dir-z)) (Math/sqrt norm))))
        [vel-x vel-y vel-z] (:vel player)
        [px py pz] (:pos player)
        nvx (+ (- vel-x (* vel-x diff)) (/ (* diff acc-x) rate))
        nvy (- vel-y (* grav time))
        nvz (+ (- vel-z (* vel-z diff)) (/ (* diff acc-z) rate))
        npx (+ px (/ (* (- time (/ diff rate)) acc-x) rate) (/ (* diff vel-x) rate))
        npy (+ py (* -0.5 grav time time) (* vel-y time))
        npz (+ pz (/ (* (- time (/ diff rate)) acc-z) rate) (/ (* diff vel-z) rate))
        scale (double map-box-scale)
        bound (- scale (:radius player))
        pos-x (max (min bound npx) (- bound))
        pos-y (max (min bound npy) (- (:height player) scale))
        pos-z (max (min bound npz) (- bound))]
    (assoc player
           :vel [(if (not= npx pos-x) 0.0 nvx)
                 (if (not= npy pos-y) (if (bit? 16) 8.4375 0.0) nvy)
                 (if (not= npz pos-z) 0.0 nvz)]
           :pos [pos-x pos-y pos-z])))

(defn- update-players [players player-count dt-ns]
  (reduce (fn [ps i] (update ps i update-player dt-ns)) players (range player-count)))

(defn- draw-circle [renderer r x y]
  (r/draw-lines! renderer
                 (for [i (range circle-draw-sides-len)
                       :let [ang (/ (* 2.0 Math/PI i) circle-draw-sides)]]
                   [(+ x (* r (Math/cos ang))) (+ y (* r (Math/sin ang)))])))

(defn- draw-clipped-segment [renderer ax ay az bx by bz x y z w]
  (when-not (and (>= az (- w)) (>= bz (- w)))
    (let [dx (- ax bx)
          dy (- ay by)
          [ax ay az bx by bz]
          (cond
            (> az (- w)) (let [t (/ (- (- w) bz) (- az bz))]
                           [(+ bx (* dx t)) (+ by (* dy t)) (- w) bx by bz])
            (> bz (- w)) (let [t (/ (- (- w) az) (- bz az))]
                           [ax ay az (- ax (* dx t)) (- ay (* dy t)) (- w)])
            :else [ax ay az bx by bz])
          ax (/ (* (- z) ax) az)
          ay (/ (* (- z) ay) az)
          bx (/ (* (- z) bx) bz)
          by (/ (* (- z) by) bz)]
      (r/draw-line! renderer (+ x ax) (- y ay) (+ x bx) (- y by)))))

(defn- draw [renderer edges players player-count debug-string]
  (let [[w h] (r/output-size renderer)]
    (r/set-draw-color! renderer 0 0 0 255)
    (r/clear! renderer)
    (when (pos? player-count)
      (let [wf (double w)
            hf (double h)
            part-hor (if (> player-count 2) 2 1)
            part-ver (if (> player-count 1) 2 1)
            size-hor (/ wf part-hor)
            size-ver (/ hf part-ver)]
        (doseq [i (range player-count)]
          (let [player (nth players i)
                mod-x (double (mod i part-hor))
                mod-y (/ (double i) part-hor)
                hor-origin (* (+ mod-x 0.5) size-hor)
                ver-origin (* (+ mod-y 0.5) size-ver)
                cam-origin (* 0.5 (Math/sqrt (+ (* size-hor size-hor) (* size-ver size-ver))))
                hor-offset (* mod-x size-hor)
                ver-offset (* mod-y size-ver)
                _ (r/set-clip-rect! renderer {:x (long hor-offset) :y (long ver-offset)
                                              :w (long size-hor) :h (long size-ver)})
                [x0 y0 z0] (:pos player)
                yaw-rad (* bin-rad (:yaw player))
                pitch-rad (* bin-rad (:pitch player))
                cos-yaw (Math/cos yaw-rad)
                sin-yaw (Math/sin yaw-rad)
                cos-pitch (Math/cos pitch-rad)
                sin-pitch (Math/sin pitch-rad)
                m0 cos-yaw, m1 0.0, m2 (- sin-yaw)
                m3 (* sin-yaw sin-pitch), m4 cos-pitch, m5 (* cos-yaw sin-pitch)
                m6 (* sin-yaw cos-pitch), m7 (- sin-pitch), m8 (* cos-yaw cos-pitch)]
            (r/set-draw-color! renderer 64 64 64 255)
            (doseq [[l0 l1 l2 l3 l4 l5] edges]
              (let [ax (+ (* m0 (- l0 x0)) (* m1 (- l1 y0)) (* m2 (- l2 z0)))
                    ay (+ (* m3 (- l0 x0)) (* m4 (- l1 y0)) (* m5 (- l2 z0)))
                    az (+ (* m6 (- l0 x0)) (* m7 (- l1 y0)) (* m8 (- l2 z0)))
                    bx (+ (* m0 (- l3 x0)) (* m1 (- l4 y0)) (* m2 (- l5 z0)))
                    by (+ (* m3 (- l3 x0)) (* m4 (- l4 y0)) (* m5 (- l5 z0)))
                    bz (+ (* m6 (- l3 x0)) (* m7 (- l4 y0)) (* m8 (- l5 z0)))]
                (draw-clipped-segment renderer ax ay az bx by bz hor-origin ver-origin cam-origin 1.0)))
            (doseq [j (range player-count) :when (not= i j)]
              (let [target (nth players j)
                    [cr cg cb] (:color target)]
                (r/set-draw-color! renderer cr cg cb 255)
                (doseq [k [0 1]]
                  (let [rx (- (nth (:pos target) 0) x0)
                        ry (+ (- (nth (:pos target) 1) y0) (* (- (:radius target) (:height target)) k))
                        rz (- (nth (:pos target) 2) z0)
                        dx (+ (* m0 rx) (* m1 ry) (* m2 rz))
                        dy (+ (* m3 rx) (* m4 ry) (* m5 rz))
                        dz (+ (* m6 rx) (* m7 ry) (* m8 rz))
                        r-eff (/ (* (:radius target) cam-origin) dz)]
                    (when (neg? dz)
                      (draw-circle renderer r-eff (- hor-origin (/ (* cam-origin dx) dz)) (+ ver-origin (/ (* cam-origin dy) dz))))))))
            (r/set-draw-color! renderer 255 255 255 255)
            (r/draw-line! renderer hor-origin (- ver-origin 10) hor-origin (+ ver-origin 10))
            (r/draw-line! renderer (- hor-origin 10) ver-origin (+ hor-origin 10) ver-origin)))))
    (r/set-clip-rect! renderer nil)
    (r/set-draw-color! renderer 255 255 255 255)
    (r/debug-text! renderer 0 0 debug-string)
    (r/present! renderer)))

(defn- init-players [len]
  (vec (for [i (range len)
             :let [odd? (pos? (bit-and i 1))
                   base (fn [bit] (if (pos? (bit-and (bit-shift-left 1 (quot i 2)) bit)) 0 0xff))
                   channel (fn [c] (if odd? c (bit-and (bit-not c) 0xff)))]]
         {:pos [(* 8.0 (if odd? -1.0 1.0))
                0.0
                (* 8.0 (if odd? -1.0 1.0) (if (pos? (bit-and i 2)) -1.0 1.0))]
          :vel [0.0 0.0 0.0]
          :yaw (mod (+ 0x20000000 (if odd? 0x80000000 0) (if (pos? (bit-and i 2)) 0x40000000 0)) two-32)
          :pitch (- 0x08000000)
          :radius 0.5
          :height 1.5
          :wasd 0
          :mouse 0
          :keyboard 0
          :color [(channel (base 2)) (channel (base 1)) (channel (base 4))]})))

(defn- init-edges [scale]
  (let [r (double scale)
        m [0 1, 1 3, 3 2, 2 0,
           7 6, 6 4, 4 5, 5 7,
           6 2, 3 7, 0 4, 5 1]
        corner (fn [c j] (if (pos? (bit-and c (bit-shift-left 1 j))) r (- r)))
        box (for [i (range 12)]
              (vec (concat (for [j (range 3)] (corner (nth m (* i 2)) j))
                           (for [j (range 3)] (corner (nth m (inc (* i 2))) j)))))
        grid (fn [f] (for [i (range scale)
                           :let [d (double (* i 2))]]
                       (vec (mapcat #(f % d) [0 1]))))]
    (vec (concat box
                 (grid (fn [j d] [(if (= j 1) r (- r)) (- r) (- d r)]))
                 (grid (fn [j d] [(- d r) (- r) (if (= j 1) r (- r))]))))))

(defn init [state _]
  (sdl/set-app-metadata! "Example splitscreen shooter game" "1.0" "com.example.woodeneye-008")
  (doseq [[k v] extended-metadata]
    (sdl/set-app-metadata-property! k v))
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/demo/woodeneye-008" 640 480 :resizable)]
      (reset! state {:window window :renderer renderer
                     :player-count 1
                     :players (init-players max-player-count)
                     :edges (init-edges map-box-scale)
                     :debug-string ""
                     :accu 0 :last 0 :past 0})
      (r/set-vsync! renderer false)
      (mouse/set-window-relative-mouse-mode! window true)
      (sdl/set-hint-with-priority! :windows-raw-keyboard "1" :override)
      :continue)))

(defn- claim-device
  "Give device `id` (under `k`, :mouse or :keyboard) to the first player without one."
  [s k id]
  (if-let [i (first (filter #(zero? (get-in s [:players % k])) (range max-player-count)))]
    (-> s
        (assoc-in [:players i k] id)
        (update :player-count max (inc i)))
    s))

(def ^:private wasd-bits {:w 1 :a 2 :s 4 :d 8 :space 16})

(defn event [state e]
  (let [{:keys [players player-count]} @state]
    (case (:type e)
      :quit :success
      :mouse-removed (do (swap! state update :players
                                (fn [ps] (mapv #(if (= (:which e) (:mouse %)) (assoc % :mouse 0) %) ps)))
                         :continue)
      :keyboard-removed (do (swap! state update :players
                                   (fn [ps] (mapv #(if (= (:which e) (:keyboard %)) (assoc % :keyboard 0) %) ps)))
                            :continue)
      :mouse-motion (let [id (:which e)]
                      (if-let [index (whose-mouse id players player-count)]
                        (swap! state update-in [:players index]
                               (fn [p] (-> p
                                           (update :yaw #(mod (- % (* (long (:xrel e)) 0x00080000)) two-32))
                                           (update :pitch #(max (- 0x40000000) (min 0x40000000 (- % (* (long (:yrel e)) 0x00080000))))))))
                        (when-not (zero? id)
                          (swap! state claim-device :mouse id)))
                      :continue)
      :mouse-button-down (do (when-let [index (whose-mouse (:which e) players player-count)]
                               (swap! state update :players shoot index player-count))
                             :continue)
      :key-down (let [id (:which e)]
                  (if-let [index (whose-keyboard id players player-count)]
                    (when-let [b (wasd-bits (:key e))]
                      (swap! state update-in [:players index :wasd] bit-or b))
                    (when-not (zero? id)
                      (swap! state claim-device :keyboard id)))
                  :continue)
      :key-up (if (= :escape (:key e))
                :success
                (do (when-let [index (whose-keyboard (:which e) players player-count)]
                      (when-let [b (wasd-bits (:key e))]
                        (swap! state update-in [:players index :wasd] bit-and (bit-and (bit-not b) 31))))
                    :continue))
      :continue)))

(defn iterate [state]
  (let [now (timer/ticks-ns)
        {:keys [renderer edges past last accu]} @state
        dt-ns (- now past)]
    (swap! state (fn [s] (update s :players update-players (:player-count s) dt-ns)))
    (let [{:keys [players player-count debug-string]} @state]
      (draw renderer edges players player-count debug-string))
    (swap! state (fn [s]
                   (let [s (if (> (- now last) 999999999)
                             (assoc s :last now :debug-string (str accu " fps") :accu 0)
                             s)]
                     (-> s (assoc :past now) (update :accu inc)))))
    (let [elapsed (- (timer/ticks-ns) now)]
      (when (< elapsed 999999)
        (timer/delay-ns! (- 999999 elapsed))))
    :continue))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate} args))
