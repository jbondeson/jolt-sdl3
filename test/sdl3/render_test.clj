(ns sdl3.render-test
  "The renderer, headless: a software renderer drawing into a surface."
  (:require [clojure.test :refer [deftest is testing]]
            [sdl3.render :as render]
            [sdl3.surface :as surface]))

(defn- with-software-renderer [f]
  (let [s (surface/create-surface 8 8 :rgba32)
        ren (render/create-software-renderer s)]
    (try (f ren)
         (finally
           (render/destroy-renderer! ren)
           (surface/destroy-surface! s)))))

(deftest texture-scale-modes
  (with-software-renderer
    (fn [ren]
      (testing "textures start with the renderer's default, :linear unless it's changed"
        (is (= :linear (render/default-texture-scale-mode ren)))
        (let [t (render/create-texture ren :rgba32 :static 2 2)]
          (is (= :linear (render/texture-scale-mode t)))
          (render/destroy-texture! t)))
      (testing "changing the default changes what new textures start with"
        (render/set-default-texture-scale-mode! ren :nearest)
        (is (= :nearest (render/default-texture-scale-mode ren)))
        (let [t (render/create-texture ren :rgba32 :static 2 2)]
          (is (= :nearest (render/texture-scale-mode t)))
          (testing "and a texture can still set its own"
            (render/set-texture-scale-mode! t :linear)
            (is (= :linear (render/texture-scale-mode t))))
          (render/destroy-texture! t))))))
