(ns examples.sdl.asyncio.load-bitmaps
  "Port of SDL's examples/asyncio/01-load-bitmaps: load bitmaps with asynchronous
  i/o and render them.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.asyncio :as aio]
            [sdl3.io :as io]
            [sdl3.messagebox :as mb]
            [sdl3.render :as r]
            [sdl3.surface :as surface]
            [examples.sdl.app :as app]
            [examples.sdl.assets :as assets]))

(def ^:private pngs ["sample.png" "gamepad_front.png" "speaker.png" "icon2x.png"])

(def ^:private texture-rects
  [{:x 116 :y 156 :w 408 :h 167}
   {:x 20 :y 200 :w 96 :h 60}
   {:x 525 :y 180 :w 96 :h 96}
   {:x 288 :y 375 :w 64 :h 64}])

(defn- fail
  "The C example reports its errors in a message box."
  [title e]
  (mb/show-simple! :error title (or (:sdl/error (ex-data e)) (ex-message e)))
  :failure)

(defn init [state _]
  (try
    (sdl/init! :video)
    (catch clojure.lang.ExceptionInfo e (fail "Couldn't initialize SDL!" e)))
  (or (when-not (contains? (sdl/was-init) :video) :failure)
      (try
        (let [[window renderer] (r/create-window-and-renderer "examples/asyncio/load-bitmaps" 640 480 :resizable)]
          (r/set-logical-presentation! renderer 640 480 :letterbox)
          (let [queue (aio/create-queue)]
            (swap! state assoc :window window :renderer renderer :queue queue
                   :textures (vec (repeat (count pngs) nil)))
            ;; Load some .png files asynchronously (the C example loads them from
            ;; wherever the app is run from), put them in the same queue.
            (doseq [[i png] (map-indexed vector pngs)]
              ;; you _should_ check for failure, but we'll just go on without files here.
              ;; attach the index as app-specific data, so we can see it later.
              (try (aio/load-file! (assets/path png) queue i)
                   (catch clojure.lang.ExceptionInfo _ nil)))
            :continue))
        (catch clojure.lang.ExceptionInfo e (fail "Couldn't create window/renderer!" e)))))

(defn event [_ e]
  (if (= :quit (:type e)) :success :continue))

(defn iterate [state]
  (let [{:keys [renderer queue]} @state
        outcome (aio/poll-result queue)]  ; a .png file load has finished?
    (or
     (when (and outcome (= :complete (:result outcome)))
       ;; this might be _any_ of the pngs; they might finish loading in any order.
       ;; The tag we gave load-file! is the png's index.
       (let [i (:tag outcome)]
         (when (and (integer? i) (< -1 i (count pngs)))  ; (just in case.)
           (when-let [surf (try (surface/load-png-io (io/from-bytes (:data outcome)) true)
                                (catch clojure.lang.ExceptionInfo _ nil))]
             ;; the renderer is not multithreaded, so create the texture here once the
             ;; data loads.
             (try
               (swap! state assoc-in [:textures i] (r/create-texture-from-surface renderer surf))
               nil
               (catch clojure.lang.ExceptionInfo e (fail "Couldn't create texture!" e))
               (finally (surface/destroy-surface! surf)))))))
     ;; (sdl3.asyncio frees the loaded buffer once the result is collected.)
     (do
       (r/set-draw-color! renderer 0 0 0 255)
       (r/clear! renderer)
       (doseq [[tex rect] (map vector (:textures @state) texture-rects)
               :when tex]
         (r/render-texture! renderer tex nil rect))
       (r/present! renderer)
       :continue))))

(defn quit [state _]
  (let [{:keys [queue textures]} @state]
    (when queue (aio/destroy-queue! queue))
    (doseq [t textures :when t] (r/destroy-texture! t))))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
