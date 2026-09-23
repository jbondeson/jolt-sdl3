(ns examples.sdl.run
  "Run one of the SDL example ports by its SDL name:

      jolt sdl-example                      # list them
      jolt sdl-example renderer/clear       # or renderer/01-clear, as SDL numbers it"
  (:require [clojure.string :as str]))

(def examples
  "SDL's examples, in SDL's order: [sdl-id namespace description]."
  [["renderer/01-clear" 'examples.sdl.renderer.clear "fade the window between colors"]
   ["renderer/02-primitives" 'examples.sdl.renderer.primitives "lines, rectangles and points"]
   ["renderer/03-lines" 'examples.sdl.renderer.lines "single lines, a connected batch, and a ring of random colors"]
   ["renderer/04-points" 'examples.sdl.renderer.points "500 points drifting across the screen"]
   ["renderer/05-rectangles" 'examples.sdl.renderer.rectangles "outlined and filled rectangles, singly and in batches"]
   ["renderer/06-textures" 'examples.sdl.renderer.textures "a PNG texture drawn several times"]
   ["renderer/07-streaming-textures" 'examples.sdl.renderer.streaming-textures "a streaming texture redrawn every frame"]
   ["renderer/08-rotating-textures" 'examples.sdl.renderer.rotating-textures "a spinning texture"]
   ["renderer/09-scaling-textures" 'examples.sdl.renderer.scaling-textures "a texture growing and shrinking"]
   ["renderer/10-geometry" 'examples.sdl.renderer.geometry "colored, textured and indexed triangles"]
   ["renderer/11-color-mods" 'examples.sdl.renderer.color-mods "texture color modulation"]
   ["renderer/14-viewport" 'examples.sdl.renderer.viewport "drawing through different viewports"]
   ["renderer/15-cliprect" 'examples.sdl.renderer.cliprect "a clipping rectangle sliding over a texture"]
   ["renderer/17-read-pixels" 'examples.sdl.renderer.read-pixels "reading the frame back and processing it on the CPU"]
   ["renderer/18-debug-text" 'examples.sdl.renderer.debug-text "SDL's built-in debug font"]
   ["renderer/19-affine-textures" 'examples.sdl.renderer.affine-textures "a spinning cube from affine-mapped textures"]
   ["audio/01-simple-playback" 'examples.sdl.audio.simple-playback "a 440Hz tone fed from the main loop"]
   ["audio/02-simple-playback-callback" 'examples.sdl.audio.simple-playback-callback "a 440Hz tone fed from a stream callback"]
   ["audio/03-load-wav" 'examples.sdl.audio.load-wav "load a .wav and play it on a loop"]
   ["audio/04-multiple-streams" 'examples.sdl.audio.multiple-streams "two .wav files mixed on one device"]
   ["audio/05-planar-data" 'examples.sdl.audio.planar-data "buttons that play sounds on the left or right channel"]
   ["input/01-joystick-polling" 'examples.sdl.input.joystick-polling "draw a joystick's axes, buttons and hats every frame"]
   ["input/02-joystick-events" 'examples.sdl.input.joystick-events "report joystick events as scrolling text"]
   ["input/03-gamepad-polling" 'examples.sdl.input.gamepad-polling "draw gamepad input over a picture of a gamepad"]
   ["input/04-gamepad-events" 'examples.sdl.input.gamepad-events "report gamepad events as scrolling text"]
   ["camera/01-read-and-draw" 'examples.sdl.camera.read-and-draw "show the first camera's frames"]
   ["pen/01-drawing-lines" 'examples.sdl.pen.drawing-lines "draw with a pen, darker for harder pressure"]
   ["asyncio/01-load-bitmaps" 'examples.sdl.asyncio.load-bitmaps "load PNGs with async IO and draw them"]
   ["misc/01-power" 'examples.sdl.misc.power "show battery and power status"]
   ["misc/02-clipboard" 'examples.sdl.misc.clipboard "copy the time to the clipboard and paste text back"]
   ["demo/01-snake" 'examples.sdl.demo.snake "the Snake game"]
   ["demo/02-woodeneye-008" 'examples.sdl.demo.woodeneye-008 "split-screen shooter, one player per mouse and keyboard"]
   ["demo/03-infinite-monkeys" 'examples.sdl.demo.infinite-monkeys "monkeys type out a text at random"]
   ["demo/04-bytepusher" 'examples.sdl.demo.bytepusher "a BytePusher VM; drop a program on the window"]])

(defn- lookup [name]
  (let [n (str/replace name #"\.clj$" "")]
    (some (fn [[id ns _ :as ex]]
            (when (or (= n id)
                      (= n (str/replace id #"/\d+-" "/"))
                      (= n (str ns)))
              ex))
          examples)))

(defn- list-examples []
  (println "SDL example ports (jolt sdl-example <name>):")
  (doseq [[id _ desc] examples]
    (println (format "  %-34s %s" (str/replace id #"/\d+-" "/") desc))))

(defn -main [& [name & args]]
  (if-not name
    (list-examples)
    (if-let [[_ ns] (lookup name)]
      (do (require ns)
          (apply (ns-resolve ns '-main) args))
      (do (println "no SDL example named" name)
          (list-examples)
          (System/exit 1)))))
