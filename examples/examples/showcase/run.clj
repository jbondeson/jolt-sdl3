(ns examples.showcase.run
  "Run one of this library's own showcase examples by name:

      jolt showcase           # list them
      jolt showcase bounce")

(def examples
  "The showcase examples: [name namespace description]."
  [["hello" 'examples.showcase.hello "the smallest useful program: a window, a square, quit on Escape"]
   ["bounce" 'examples.showcase.bounce "bouncing rects: the renderer, decoded events and keyboard state"]
   ["gpu-clear" 'examples.showcase.gpu-clear "the GPU API's swapchain loop, clearing to a cycling color"]
   ["tone" 'examples.showcase.tone "a two-second tone from an audio stream callback"]
   ["tray" 'examples.showcase.tray "a tray menu that opens a native file dialog"]])

(defn- list-examples []
  (println "Showcase examples (jolt showcase <name>):")
  (doseq [[name _ desc] examples]
    (println (format "  %-12s %s" name desc))))

(defn -main [& [name & args]]
  (if-not name
    (list-examples)
    (if-let [[_ ns] (some #(when (= name (first %)) %) examples)]
      (do (require ns)
          (apply (ns-resolve ns '-main) args))
      (do (println "no showcase example named" name)
          (list-examples)
          (System/exit 1)))))
