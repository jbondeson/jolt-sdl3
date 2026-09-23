(ns sdl3.gpu-test
  "Headless GPU tests: buffer and texture round trips on any driver, and a full
  shader pipeline on Metal (MSL source compiles at runtime; the other drivers
  would need precompiled SPIR-V or DXIL)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [jolt.ffi :as ffi]
            [sdl3.core :as sdl]
            [sdl3.gpu :as gpu]))

(def ^:dynamic *dev* nil)

(use-fixtures :once
  (fn [f]
    (sdl/init! :video)
    (try
      (if-not (gpu/supports-shader-formats? [:spirv :msl :dxil])
        (println "sdl3.gpu-test: no GPU driver available; skipping")
        ;; a driver can be present with no usable device behind it (a CI VM with no
        ;; GPU), so a failed create skips too
        (if-let [dev (try (gpu/create-device {:shader-formats [:spirv :msl :dxil]})
                          (catch clojure.lang.ExceptionInfo e
                            (println "sdl3.gpu-test: no GPU device (" (.getMessage e) "); skipping")
                            nil))]
          (try (binding [*dev* dev] (f))
               (finally (gpu/destroy-device! dev)))))
      (finally (sdl/quit!)))))

(defn- floats [^bytes bs]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (sdl/bytes->ptr a bs)] (vec (ffi/read-array p :float (quot n 4))))))

(defn- rgba [bs] (mapv #(bit-and % 0xff) bs))

(deftest device
  (is (string? (gpu/device-driver *dev*)))
  (is (seq (gpu/shader-formats *dev*))))

(deftest buffer-round-trip
  (let [buf (gpu/create-buffer *dev* {:usage [:vertex] :size 16})]
    (try
      (gpu/upload! *dev* buf (float-array [1.5 -2.0 3.25 4.0]))
      (is (= [1.5 -2.0 3.25 4.0] (floats (gpu/download *dev* buf 16))))
      (finally (gpu/release-buffer! *dev* buf)))))

(deftest texture-clear-and-upload
  (let [tex (gpu/create-texture *dev* {:format :r8g8b8a8-unorm :usage [:color-target :sampler] :width 4 :height 4})]
    (try
      (let [cb (gpu/acquire-command-buffer *dev*)]
        (gpu/end-render-pass! (gpu/begin-render-pass cb [{:texture tex :clear-color [1.0 0.5 0 1] :load-op :clear :store-op :store}]))
        (gpu/submit! cb)
        (gpu/wait-for-idle! *dev*))
      (is (= [255 128 0 255] (rgba (gpu/download-texture *dev* tex 2 2 1 1 4))))
      (gpu/upload-texture! *dev* tex 1 1 1 1 (byte-array [10 20 30 40]))
      (is (= [10 20 30 40] (rgba (gpu/download-texture *dev* tex 1 1 1 1 4))))
      (finally (gpu/release-texture! *dev* tex)))))

(deftest create-info-errors-raise
  (is (thrown? clojure.lang.ExceptionInfo
               (gpu/create-texture *dev* {:format :no-such-format :width 1 :height 1}))))

(def ^:private msl
  "#include <metal_stdlib>
using namespace metal;
struct VIn { float2 pos [[attribute(0)]]; };
struct VOut { float4 pos [[position]]; };
vertex VOut vs_main(VIn in [[stage_in]]) { VOut o; o.pos = float4(in.pos, 0, 1); return o; }
fragment float4 fs_main(VOut in [[stage_in]], constant float4 &color [[buffer(0)]]) { return color; }")

(deftest msl-pipeline
  (if-not (contains? (gpu/shader-formats *dev*) :msl)
    (println "sdl3.gpu-test/msl-pipeline: device takes no MSL; skipping")
    (let [vs (gpu/create-shader *dev* {:code msl :entrypoint "vs_main" :format :msl :stage :vertex})
          fs (gpu/create-shader *dev* {:code msl :entrypoint "fs_main" :format :msl :stage :fragment :num-uniform-buffers 1})
          pipeline (gpu/create-graphics-pipeline
                    *dev* {:vertex-shader vs :fragment-shader fs
                           :primitive-type :trianglelist
                           :vertex-input-state {:vertex-buffer-descriptions [{:slot 0 :pitch 8 :input-rate :vertex}]
                                                :vertex-attributes [{:location 0 :buffer-slot 0 :format :float2 :offset 0}]}
                           :target-info {:color-target-descriptions [{:format :r8g8b8a8-unorm}]}})
          _ (gpu/release-shader! *dev* vs)
          _ (gpu/release-shader! *dev* fs)
          ;; one triangle covering the whole target
          vbuf (gpu/create-buffer *dev* {:usage :vertex :size 24})
          tex (gpu/create-texture *dev* {:format :r8g8b8a8-unorm :usage :color-target :width 4 :height 4})]
      (try
        (gpu/upload! *dev* vbuf (float-array [-1 -1 3 -1 -1 3]))
        (let [cb (gpu/acquire-command-buffer *dev*)
              pass (gpu/begin-render-pass cb [{:texture tex :clear-color [0 0 0 1] :load-op :clear :store-op :store}])]
          (gpu/bind-graphics-pipeline! pass pipeline)
          (gpu/bind-vertex-buffers! pass 0 [{:buffer vbuf}])
          (gpu/push-fragment-uniform-data! cb 0 (float-array [0.0 1.0 0.0 1.0]))
          (gpu/set-viewport! pass {:w 4 :h 4})
          (gpu/draw-primitives! pass 3)
          (gpu/end-render-pass! pass)
          (gpu/submit! cb)
          (gpu/wait-for-idle! *dev*))
        (is (= [0 255 0 255] (rgba (gpu/download-texture *dev* tex 1 2 1 1 4))))
        (finally
          (gpu/release-texture! *dev* tex)
          (gpu/release-buffer! *dev* vbuf)
          (gpu/release-graphics-pipeline! *dev* pipeline))))))
