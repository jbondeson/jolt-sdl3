(ns examples.showcase.text
  "SDL_ttf and SDL_image together: a picture loaded with sdl3.image, and a line of
  text you can type into, drawn through an SDL_ttf renderer text engine. Needs
  libSDL3_ttf and libSDL3_image (`brew install sdl3_ttf sdl3_image`).
  `jolt showcase text` runs it."
  (:require [sdl3.core :as sdl]
            [sdl3.events :as ev]
            [sdl3.image :as img]
            [sdl3.keyboard :as kb]
            [sdl3.render :as r]
            [sdl3.ttf :as ttf]
            [sdl3.video :as video]
            [examples.sdl.assets :as assets]))

(def ^:private font-candidates
  ["/System/Library/Fonts/Supplemental/Arial.ttf"
   "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"
   "/usr/share/fonts/TTF/DejaVuSans.ttf"
   "C:/Windows/Fonts/arial.ttf"])

(defn -main [& [font-path]]
  (let [font-path (or font-path (first (filter #(.exists (java.io.File. ^String %)) font-candidates)))]
    (cond
      (not (and (ttf/available?) (img/available?)))
      (println "This example needs libSDL3_ttf and libSDL3_image: brew install sdl3_ttf sdl3_image")

      (not font-path)
      (println "No system font found; pass one: jolt showcase text /path/to/font.ttf")

      :else
      (sdl/with-sdl [:video]
        (ttf/with-ttf
          (let [[win ren] (r/create-window-and-renderer "jolt-sdl3 text" 640 360 [:resizable])
                picture (img/load-texture ren (assets/path "sample.png"))
                title-font (ttf/open-font font-path 28)
                body-font (ttf/open-font font-path 20)
                engine (ttf/create-renderer-text-engine ren)
                title (ttf/create-text engine title-font "SDL_ttf + SDL_image")
                typed (ttf/create-text engine body-font "Type something: ")
                hint (ttf/create-text engine body-font "Backspace deletes, Escape quits")]
            (ttf/set-text-color! title [240 200 60])
            (ttf/set-text-color! hint [150 150 170])
            (ttf/set-wrap-width! typed 600)
            (r/set-vsync! ren true)
            (kb/start-text-input! win)
            (loop []
              (let [quit? (reduce
                           (fn [quit? e]
                             (case (:type e)
                               :quit true
                               :text-input (do (ttf/append-text! typed (:text e)) quit?)
                               :key-down (case (:key e)
                                           :escape true
                                           :backspace (let [s (ttf/text typed)]
                                                        (when (> (count s) (count "Type something: "))
                                                          (ttf/set-text! typed (subs s 0 (dec (count s)))))
                                                        quit?)
                                           quit?)
                               quit?))
                           false
                           (ev/poll-all!))]
                (r/set-draw-color! ren 24 28 40)
                (r/clear! ren)
                (let [[pw ph] (r/texture-size picture)]
                  (r/render-texture! ren picture nil {:x 20 :y 70 :w pw :h ph}))
                (ttf/draw-renderer-text! title 20 20)
                (ttf/draw-renderer-text! typed 20 260)
                (ttf/draw-renderer-text! hint 20 320)
                (r/present! ren)
                (when-not quit? (recur))))
            (kb/stop-text-input! win)
            (doseq [t [title typed hint]] (ttf/destroy-text! t))
            (ttf/destroy-text-engine! engine)
            (ttf/close-font! title-font)
            (ttf/close-font! body-font)
            (r/destroy-texture! picture)
            (r/destroy-renderer! ren)
            (video/destroy-window! win)))))))
