(ns examples.sdl.pen.drawing-lines
  "Port of SDL's examples/pen/01-drawing-lines: read pen/stylus input and draw
  lines. Darker lines for harder pressure.

  The original C is public domain; so is this port."
  (:require [sdl3.core :as sdl]
            [sdl3.render :as r]
            [examples.sdl.app :as app]))

(defn init [state _]
  (sdl/set-app-metadata! "Example Pen Drawing Lines" "1.0" "com.example.pen-drawing-lines")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video)
    (let [[window renderer] (r/create-window-and-renderer "examples/pen/drawing-lines" 640 480 0)
          ;; we make a render target so we can draw lines to it and not have to record and redraw every pen stroke each frame.
          ;; Instead rendering a frame for us is a single texture draw.

          ;; make sure the render target matches output size (for hidpi displays, etc) so drawing matches the pen's position on a tablet display.
          [w h] (r/output-size renderer)
          render-target (r/create-texture renderer :rgba8888 :target w h)]
      ;; just blank the render target to gray to start.
      (r/set-render-target! renderer render-target)
      (r/set-draw-color! renderer 100 100 100 255)
      (r/clear! renderer)
      (r/set-render-target! renderer nil)
      (r/set-draw-blend-mode! renderer :blend)
      (swap! state assoc :window window :renderer renderer :render-target render-target
             :pressure 0.0 :previous-touch-x -1.0 :previous-touch-y -1.0 :tilt-x 0.0 :tilt-y 0.0)
      :continue)))

(defn event [state e]
  (case (:type e)
    :quit :success
    ;; There are several events that track the specific stages of pen activity,
    ;; but we're only going to look for motion and pressure, for simplicity.
    :pen-motion
    (let [{:keys [renderer render-target pressure previous-touch-x previous-touch-y]} @state]
      ;; you can check for when the pen is touching, but if pressure > 0.0f, it's definitely touching!
      (if (pos? pressure)
        (do (when (>= previous-touch-x 0.0) ; only draw if we're moving while touching
              ;; draw with the alpha set to the pressure, so you effectively get a fainter line for lighter presses.
              (r/set-render-target! renderer render-target)
              (r/set-draw-color-float! renderer 0 0 0 pressure)
              (r/draw-line! renderer previous-touch-x previous-touch-y (:x e) (:y e)))
            (swap! state assoc :previous-touch-x (:x e) :previous-touch-y (:y e)))
        (swap! state assoc :previous-touch-x -1.0 :previous-touch-y -1.0))
      :continue)
    :pen-axis
    (do (case (:axis e)
          :pressure (swap! state assoc :pressure (:value e)) ; remember new pressure for later draws.
          :xtilt (swap! state assoc :tilt-x (:value e))
          :ytilt (swap! state assoc :tilt-y (:value e))
          nil)
        :continue)
    :continue))

(defn iterate [state]
  (let [{:keys [renderer render-target tilt-x tilt-y]} @state]
    ;; make sure we're drawing to the window and not the render target
    (r/set-render-target! renderer nil)
    (r/set-draw-color! renderer 0 0 0 255)
    (r/clear! renderer) ; just in case.
    (r/render-texture! renderer render-target)
    (r/debug-text! renderer 0 8 (format "Tilt: %f %f" (double tilt-x) (double tilt-y)))
    (r/present! renderer)
    :continue))

(defn quit [state _]
  (when-let [t (:render-target @state)]
    (r/destroy-texture! t)))
  ;; SDL will clean up the window/renderer for us.

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))
