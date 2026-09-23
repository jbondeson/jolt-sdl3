(ns examples.sdl.demo.bytepusher
  "Port of SDL's examples/demo/04-bytepusher: an implementation of the
  BytePusher VM, a tiny machine that only ever copies bytes. Drop a BytePusher
  program on the window (or pass its path) to load and run it; Enter switches
  between positional and symbolic keyboard input, Escape quits.

      jolt sdl-example demo/bytepusher [program.BytePusher]

  For example programs and more information about BytePusher, see
  https://esolangs.org/wiki/BytePusher

  The VM's 16 MiB of RAM is a jolt byte-array, where the copy loop runs fastest;
  each frame the screen page and the audio samples are copied out to native
  memory for SDL.

  The original C is public domain; so is this port."
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [sdl3.audio :as audio]
            [sdl3.core :as sdl]
            [sdl3.io :as io]
            [sdl3.keyboard :as kb]
            [sdl3.pixels :as pixels]
            [sdl3.render :as r]
            [sdl3.timer :as timer]
            [sdl3.video :as video]
            [examples.sdl.app :as app]))

(def screen-w 256)
(def screen-h 256)
(def ram-size 0x1000000)
(def frames-per-second 60)
(def samples-per-frame 256)
(def ns-per-second 1000000000)
(def max-audio-latency-frames 5)

(def io-keyboard 0)
(def io-pc 2)
(def io-screen-page 5)
(def io-audio-bank 6)

(def ^:private extended-metadata
  {:app-metadata-url-string "https://examples.libsdl.org/SDL3/demo/04-bytepusher/"
   :app-metadata-creator-string "SDL team"
   :app-metadata-copyright-string "Placed in the public domain"
   :app-metadata-type-string "game"})

(defmacro ^:private u8 [ram addr] `(bit-and (aget ~ram ~addr) 0xff))

(defn- read-u16 [^bytes ram addr]
  (bit-or (bit-shift-left (u8 ram addr) 8) (u8 ram (+ addr 1))))

(defn- set-status [state s]
  ;; the C formats into a SCREEN_W / 8 byte buffer, so the status holds 31 characters
  (swap! state assoc :status (subs s 0 (min (count s) (dec (quot screen-w 8))))
         :status-ticks (* frames-per-second 3)))

(defn- load
  "Read a program into RAM. Answers whether the whole stream was read."
  [state stream close?]
  (let [^bytes ram (:ram @state)]
    (swap! state assoc :display-help true) ; will set to false if load succeeds.
    (java.util.Arrays/fill ram (byte 0))
    (if-not stream
      false
      (let [ok (loop [bytes-read 0]
                 (if (>= bytes-read ram-size)
                   true
                   (let [chunk (io/read-bytes stream (min 65536 (- ram-size bytes-read)))
                         n (alength ^bytes chunk)]
                     (System/arraycopy chunk 0 ram bytes-read n)
                     (if (zero? n)
                       (= :eof (io/status stream))
                       (recur (+ bytes-read n))))))]
        (when close? (io/close! stream))
        (audio/clear! (:audiostream @state))
        (swap! state assoc :display-help (not ok))
        ok))))

(defn- filename [path]
  (last (str/split path #"[/\\]")))

(defn- load-file [state path]
  (if (load state (try (io/from-file path "rb") (catch clojure.lang.ExceptionInfo _ nil)) true)
    (do (set-status state (str "loaded " (filename path))) true)
    (do (set-status state (str "load failed: " (filename path))) false)))

(defn- print-text [renderer x y s]
  (r/set-draw-color! renderer 0 0 0 255)
  (r/debug-text! renderer (inc x) (inc y) s)
  (r/set-draw-color! renderer 0xff 0xff 0xff 255)
  (r/debug-text! renderer x y s)
  (r/set-draw-color! renderer 0 0 0 255))

(defn init [state args]
  (sdl/set-app-metadata! "SDL 3 BytePusher" "1.0" "com.example.SDL3BytePusher")
  (doseq [[k v] extended-metadata]
    (sdl/set-app-metadata-property! k v))
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :audio :video)
    (let [zoom (try
                 (let [{:keys [x y w h]} (video/display-usable-bounds (video/primary-display))
                       zoom-w (quot (quot (* (- w x) 2) 3) screen-w)
                       zoom-h (quot (quot (* (- h y) 2) 3) screen-h)]
                   (max 1 (min zoom-w zoom-h)))
                 (catch clojure.lang.ExceptionInfo _ 2))
          [window renderer] (r/create-window-and-renderer "SDL 3 BytePusher" (* screen-w zoom) (* screen-h zoom) :resizable)
          _ (r/set-logical-presentation! renderer screen-w screen-h :integer-scale)
          ;; a 6x6x6 color cube, then black
          palette (pixels/create-palette 256 (concat (for [r (range 6) g (range 6) b (range 6)]
                                                       [(* r 0x33) (* g 0x33) (* b 0x33) 255])
                                                     (repeat 40 [0 0 0 255])))
          texture (r/create-texture renderer :index8 :streaming screen-w screen-h)
          audiostream (audio/open-device-stream :playback {:format :s8 :channels 1
                                                           :freq (* samples-per-frame frames-per-second)})]
      (r/set-texture-palette! texture palette)
      (r/set-texture-scale-mode! texture :nearest)
      (audio/set-stream-gain! audiostream 0.1)  ; examples are loud!
      (audio/resume-stream-device! audiostream)
      (reset! state {:window window :renderer renderer :palette palette :texture texture
                     :audiostream audiostream
                     :ram (byte-array (+ ram-size 8))
                     ;; native copies of the screen page and one frame of audio, for SDL
                     :screen-buf (ffi/alloc (* screen-w screen-h))
                     :audio-buf (ffi/alloc samples-per-frame)
                     :status "" :status-ticks 0 :keystate 0
                     :display-help true :positional-input false
                     :last-tick (timer/ticks-ns)
                     :tick-acc ns-per-second})
      (set-status state (str "renderer: " (r/renderer-name renderer)))
      (when-let [path (first args)]
        (load-file state path))
      :continue)))

(defmacro ^:private u24
  "read_u24, inlined: the VM loop below runs it three times per instruction."
  [ram addr]
  `(let [a# ~addr]
     (bit-or (bit-shift-left (u8 ~ram a#) 16) (bit-shift-left (u8 ~ram (+ a# 1)) 8) (u8 ~ram (+ a# 2)))))

(defn- run-frame
  "One frame of the VM: 65536 copy instructions, starting at the address in IO_PC."
  [^bytes ram keystate]
  (aset ram io-keyboard (unchecked-byte (bit-shift-right keystate 8)))
  (aset ram (inc io-keyboard) (unchecked-byte keystate))
  (loop [i 0 pc (u24 ram io-pc)]
    (when (< i 65536)
      (aset ram (u24 ram (+ pc 3)) (aget ram (u24 ram pc)))
      (recur (inc i) (u24 ram (+ pc 6))))))

(defn iterate [state]
  (let [{:keys [^bytes ram renderer texture audiostream screen-buf audio-buf last-tick]} @state
        tick (timer/ticks-ns)
        delta (- tick last-tick)
        tick-acc (+ (:tick-acc @state) (* delta frames-per-second))
        updated (>= tick-acc ns-per-second)
        skip-audio (>= tick-acc (* max-audio-latency-frames ns-per-second))]
    (when skip-audio
      ;; don't let audio fall too far behind
      (audio/clear! audiostream))
    (let [tick-acc (loop [acc tick-acc]
                     (if (< acc ns-per-second)
                       acc
                       (let [acc (- acc ns-per-second)]
                         (run-frame ram (:keystate @state))
                         (when (or (not skip-audio) (< acc ns-per-second))
                           (ffi/write-array audio-buf ram (bit-shift-left (read-u16 ram io-audio-bank) 8) samples-per-frame)
                           (audio/put! audiostream [audio-buf samples-per-frame]))
                         (recur acc))))]
      (swap! state assoc :last-tick tick :tick-acc tick-acc))
    (when updated
      (ffi/write-array screen-buf ram (bit-shift-left (u8 ram io-screen-page) 16) (* screen-w screen-h))
      (r/update-texture! texture nil screen-buf screen-w))
    (r/clear! renderer)
    (if (:display-help @state)
      (do (print-text renderer 4 4 "Drop a BytePusher file in this")
          (print-text renderer 8 12 "window to load and run it!")
          (print-text renderer 4 28 "Press ENTER to switch between")
          (print-text renderer 8 36 "positional and symbolic input."))
      (r/render-texture! renderer texture))
    (when (pos? (:status-ticks @state))
      (when updated (swap! state update :status-ticks dec))
      (print-text renderer 4 (- screen-h 12) (:status @state)))
    (r/present! renderer)
    :continue))

(defn- keycode-mask [key]
  (let [k (kb/keycode key)
        index (cond (<= (kb/keycode :keycode-0) k (kb/keycode :keycode-9)) (- k (kb/keycode :keycode-0))
                    (<= (kb/keycode :a) k (kb/keycode :f)) (+ (- k (kb/keycode :a)) 10))]
    (if index (bit-shift-left 1 index) 0)))

(def ^:private scancode-index
  {:keycode-1 0x1 :keycode-2 0x2 :keycode-3 0x3 :keycode-4 0xc
   :q 0x4 :w 0x5 :e 0x6 :r 0xd
   :a 0x7 :s 0x8 :d 0x9 :f 0xe
   :z 0xa :x 0x0 :c 0xb :v 0xf})

(defn- scancode-mask [scancode]
  ;; scancodes 1-4 decode as :scancode-1 ...; the table is keyed like keycodes
  (let [k (keyword (str/replace (name scancode) #"^scancode-" "keycode-"))]
    (if-let [index (scancode-index k)] (bit-shift-left 1 index) 0)))

(defn event [state e]
  (case (:type e)
    :quit :success
    :drop-file (do (load-file state (:data e)) :continue)
    :key-down (if (= :escape (:key e))
                :success
                (do (when (= :return (:key e))
                      (swap! state #(-> % (update :positional-input not) (assoc :keystate 0)))
                      (set-status state (if (:positional-input @state)
                                          "switched to positional input"
                                          "switched to symbolic input")))
                    (swap! state update :keystate bit-or
                           (if (:positional-input @state) (scancode-mask (:scancode e)) (keycode-mask (:key e))))
                    :continue))
    :key-up (do (swap! state update :keystate bit-and
                       (bit-not (if (:positional-input @state) (scancode-mask (:scancode e)) (keycode-mask (:key e)))))
                :continue)
    :continue))

(defn quit [state result]
  (when (= :failure result)
    (println "Error:" (sdl/error)))
  (let [{:keys [audiostream texture palette renderer window screen-buf audio-buf]} @state]
    (when audiostream (audio/destroy-stream! audiostream))
    (when texture (r/destroy-texture! texture))
    (when palette (pixels/destroy-palette! palette))
    (when renderer (r/destroy-renderer! renderer))
    (when window (video/destroy-window! window))
    (when screen-buf (ffi/free screen-buf))
    (when audio-buf (ffi/free audio-buf))))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
