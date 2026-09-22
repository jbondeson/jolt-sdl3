(ns sdl3.gpu
  "The GPU API: SDL_gpu.h — a portable layer over Vulkan, Metal and Direct3D 12.

  Objects (device, textures, buffers, shaders, pipelines, command buffers,
  passes, fences) are the pointers SDL hands out; release them with the
  matching release-*! against their device.

  Every create-info struct is given as a map naming only the fields you care
  about — the rest are zero, which SDL treats as the default. Enumerations are
  keywords from the sdl3.consts gpu-* tables, flags keywords or collections of
  them, colors {:r :g :b :a} or [r g b a] (0.0-1.0), and nested arrays vectors
  of maps; the field names are SDL's, kebab-cased:

      (create-texture dev {:type :2d :format :r8g8b8a8-unorm :usage [:sampler :color-target]
                           :width 256 :height 256})

  Three spellings differ from sdl3.consts because a keyword cannot start there
  readably: texture :type is :2d :2d-array :3d :cube or :cube-array; a
  :sample-count is 1, 2, 4 or 8; an index element size is 16 or 32.

  A frame, drawn to a window:

      (let [cb (acquire-command-buffer dev)]
        (when-let [{:keys [texture]} (wait-and-acquire-swapchain-texture cb win)]
          (let [pass (begin-render-pass cb [{:texture texture :clear-color [0.1 0.1 0.2 1]
                                             :load-op :clear :store-op :store}])]
            (bind-graphics-pipeline! pass pipeline)
            (draw-primitives! pass 3)
            (end-render-pass! pass)))
        (submit! cb))

  Shaders are not compiled here: hand create-shader SPIR-V, MSL, DXIL or DXBC
  bytes (shader-formats says which the device takes). upload!, upload-texture!
  and download do the transfer-buffer and copy-pass dance for one-off copies."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.rect :as rect]
            [sdl3.raw.gpu :as gpu]
            [sdl3.raw.pixels :as pixels]))

;; ---------------------------------------------------------------------------
;; enum conversion
;; ---------------------------------------------------------------------------

(def ^:private texture-types {:2d 0 :2d-array 1 :3d 2 :cube 3 :cube-array 4})
(def ^:private sample-counts {1 0 2 1 4 2 8 3})
(def ^:private index-sizes {16 0 32 1})

(defn- en [table v] (if (keyword? v) (core/enum table v) v))
(defn- fl [table v] (core/flags table v))

(defn- tex-type [v] (cond (keyword? v) (or (texture-types v) (core/enum c/gpu-texture-type v)) :else v))
(defn- sample-count [v]
  (cond (keyword? v) (core/enum c/gpu-sample-count v)
        (contains? sample-counts v) (sample-counts v)
        :else (throw (ex-info (str "sample count must be 1, 2, 4 or 8; got " v) {:value v}))))
(defn- index-size [v]
  (cond (keyword? v) (core/enum c/gpu-index-element-size v)
        (contains? index-sizes v) (index-sizes v)
        :else (throw (ex-info (str "index element size must be 16 or 32; got " v) {:value v}))))

(defn- color [v]
  (cond (nil? v) nil
        (map? v) (merge {:a 1.0} v)
        :else (let [[r g b a] v] {:r r :g g :b b :a (if (nil? a) 1.0 a)})))

(defn- xf
  "Apply the converter in `spec` to each key of `m` that holds a value."
  [m spec]
  (reduce-kv (fn [m k f] (if (some? (get m k)) (update m k f) m)) m spec))

(defn texture-format->int [f] (en c/gpu-texture-format f))
(defn int->texture-format [v] (core/unenum c/gpu-texture-format-names v))

;; ---------------------------------------------------------------------------
;; device
;; ---------------------------------------------------------------------------

(defn drivers "The GPU drivers built into SDL, e.g. [\"metal\"] or [\"vulkan\" \"direct3d12\"]." []
  (mapv gpu/get-gpu-driver (range (gpu/get-num-gpu-drivers))))

(defn supports-shader-formats?
  "SDL_GPUSupportsShaderFormats: could a device be created that takes any of
  `formats` (:spirv :msl :metallib :dxil :dxbc), on driver `driver-name` (nil: any)?"
  ([formats] (supports-shader-formats? formats nil))
  ([formats driver-name] (gpu/gpu-supports-shader-formats (fl c/gpu-shader-format formats) driver-name)))

(defsdl supports-properties? gpu/gpu-supports-properties :pred true)

(defn create-device
  "SDL_CreateGPUDevice. Options:

    :shader-formats  the formats you can supply, e.g. [:spirv :msl :dxil]
                     (required; SDL picks a driver that takes one of them)
    :debug           validation and debug labels (default false)
    :driver          a name from (drivers), or nil for the best available"
  [{:keys [shader-formats debug driver]}]
  (core/check-ptr "SDL_CreateGPUDevice"
                  (gpu/create-gpu-device (fl c/gpu-shader-format shader-formats) (boolean debug) driver)))

(defsdl create-device-with-properties gpu/create-gpu-device-with-properties)
(defsdl destroy-device! gpu/destroy-gpu-device)
(defsdl device-driver gpu/get-gpu-device-driver)
(defsdl device-properties gpu/get-gpu-device-properties)

(defn shader-formats
  "SDL_GetGPUShaderFormats: the set of shader formats the device takes."
  [dev]
  (core/unflag c/gpu-shader-format (gpu/get-gpu-shader-formats dev)))

;; ---------------------------------------------------------------------------
;; windows and swapchains
;; ---------------------------------------------------------------------------

(defsdl claim-window! gpu/claim-window-for-gpu-device
  :doc "Make the device render to window's swapchain; do this once per window before acquiring its texture.")
(defsdl release-window! gpu/release-window-from-gpu-device)

(defn supports-swapchain-composition? [dev win comp]
  (gpu/window-supports-gpu-swapchain-composition dev win (en c/gpu-swapchain-composition comp)))

(defn supports-present-mode?
  "`mode` is :vsync, :immediate or :mailbox."
  [dev win mode]
  (gpu/window-supports-gpu-present-mode dev win (en c/gpu-present-mode mode)))

(defn set-swapchain-parameters!
  "SDL_SetGPUSwapchainParameters: `composition` :sdr :sdr-linear
  :hdr-extended-linear :hdr10-st2084; `mode` :vsync :immediate :mailbox."
  [dev win composition mode]
  (core/check-bool "SDL_SetGPUSwapchainParameters"
                   (gpu/set-gpu-swapchain-parameters dev win (en c/gpu-swapchain-composition composition) (en c/gpu-present-mode mode)))
  nil)

(defsdl set-allowed-frames-in-flight! gpu/set-gpu-allowed-frames-in-flight)

(defn swapchain-texture-format [dev win]
  (int->texture-format (gpu/get-gpu-swapchain-texture-format dev win)))

(defn- acquire [what f cb win]
  (with-outs [tex :pointer w :uint32 h :uint32]
    (core/check-bool what (f cb win tex w h))
    (let [t (ffi/read tex :pointer)]
      (when-not (ffi/null? t)
        {:texture t :width (ffi/read w :uint32) :height (ffi/read h :uint32)}))))

(defn acquire-swapchain-texture
  "SDL_AcquireGPUSwapchainTexture: {:texture :width :height}, or nil when there is
  nothing to draw to this frame (the window is minimized, or too many frames are
  in flight). Submit `cb` either way."
  [cb win]
  (acquire "SDL_AcquireGPUSwapchainTexture" gpu/acquire-gpu-swapchain-texture cb win))

(defn wait-and-acquire-swapchain-texture
  "SDL_WaitAndAcquireGPUSwapchainTexture: as acquire-swapchain-texture, blocking
  until a frame is free; still nil while minimized."
  [cb win]
  (acquire "SDL_WaitAndAcquireGPUSwapchainTexture" gpu/wait-and-acquire-gpu-swapchain-texture cb win))

(defsdl wait-for-swapchain! gpu/wait-for-gpu-swapchain)

;; ---------------------------------------------------------------------------
;; resources
;; ---------------------------------------------------------------------------

(defn create-texture
  "SDL_CreateGPUTexture. {:type :2d :format :r8g8b8a8-unorm :usage [:sampler]
  :width :height :layer-count-or-depth 1 :num-levels 1 :sample-count 1 :props}"
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUTexture"
                    (gpu/create-gpu-texture
                     dev (core/alloc-fields a gpu/gpu-texture-create-info
                                            (-> (merge {:type :2d :layer-count-or-depth 1 :num-levels 1} info)
                                                (xf {:type tex-type
                                                     :format texture-format->int
                                                     :usage #(fl c/gpu-texture-usage %)
                                                     :sample-count sample-count})))))))

(defn create-buffer
  "SDL_CreateGPUBuffer. {:usage [:vertex] :size bytes :props}; usage from
  sdl3.consts/gpu-buffer-usage (:vertex :index :indirect :graphics-storage-read ...)."
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUBuffer"
                    (gpu/create-gpu-buffer dev (core/alloc-fields a gpu/gpu-buffer-create-info
                                                                  (xf info {:usage #(fl c/gpu-buffer-usage %)}))))))

(defn create-transfer-buffer
  "SDL_CreateGPUTransferBuffer. {:usage :upload|:download :size bytes :props}."
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUTransferBuffer"
                    (gpu/create-gpu-transfer-buffer dev (core/alloc-fields a gpu/gpu-transfer-buffer-create-info
                                                                           (xf (merge {:usage :upload} info)
                                                                               {:usage #(en c/gpu-transfer-buffer-usage %)}))))))

(defn create-sampler
  "SDL_CreateGPUSampler. {:min-filter :linear :mag-filter :linear :mipmap-mode
  :nearest :address-mode-u :repeat :address-mode-v :address-mode-w
  :mip-lod-bias :max-anisotropy :compare-op :min-lod :max-lod
  :enable-anisotropy :enable-compare :props}"
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUSampler"
                    (gpu/create-gpu-sampler
                     dev (core/alloc-fields a gpu/gpu-sampler-create-info
                                            (xf info {:min-filter #(en c/gpu-filter %)
                                                      :mag-filter #(en c/gpu-filter %)
                                                      :mipmap-mode #(en c/gpu-sampler-mipmap-mode %)
                                                      :address-mode-u #(en c/gpu-sampler-address-mode %)
                                                      :address-mode-v #(en c/gpu-sampler-address-mode %)
                                                      :address-mode-w #(en c/gpu-sampler-address-mode %)
                                                      :compare-op #(en c/gpu-compare-op %)}))))))

(defn- code-fields
  "The :code/:code-size/:entrypoint/:format part of a shader or compute create-info."
  [arena {:keys [code entrypoint format] :as info}]
  (let [bs (if (string? code) (.getBytes ^String code "UTF-8") code)
        [p n] (core/bytes->ptr arena bs)]
    (assoc info
           :code p :code-size n
           :entrypoint (ffi/string->ptr arena (or entrypoint "main"))
           :format (fl c/gpu-shader-format format))))

(defn create-shader
  "SDL_CreateGPUShader.

    :code         the shader as a byte-array (MSL source may be a string)
    :entrypoint   default \"main\"
    :format       :spirv :msl :metallib :dxil or :dxbc
    :stage        :vertex or :fragment
    :num-samplers :num-storage-textures :num-storage-buffers :num-uniform-buffers
    :props"
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUShader"
                    (gpu/create-gpu-shader
                     dev (core/alloc-fields a gpu/gpu-shader-create-info
                                            (-> (code-fields a info)
                                                (xf {:stage #(en c/gpu-shader-stage %)})))))))

(defn- blend-state [m]
  (xf m {:src-color-blendfactor #(en c/gpu-blend-factor %)
         :dst-color-blendfactor #(en c/gpu-blend-factor %)
         :color-blend-op #(en c/gpu-blend-op %)
         :src-alpha-blendfactor #(en c/gpu-blend-factor %)
         :dst-alpha-blendfactor #(en c/gpu-blend-factor %)
         :alpha-blend-op #(en c/gpu-blend-op %)
         :color-write-mask #(fl c/gpu-color-component %)}))

(defn- stencil-op-state [m]
  (xf m {:fail-op #(en c/gpu-stencil-op %) :pass-op #(en c/gpu-stencil-op %)
         :depth-fail-op #(en c/gpu-stencil-op %) :compare-op #(en c/gpu-compare-op %)}))

(defn create-graphics-pipeline
  "SDL_CreateGPUGraphicsPipeline:

    {:vertex-shader vs :fragment-shader fs
     :primitive-type :trianglelist            ; :trianglestrip :linelist :linestrip :pointlist
     :vertex-input-state
       {:vertex-buffer-descriptions [{:slot 0 :pitch 24 :input-rate :vertex}]
        :vertex-attributes [{:location 0 :buffer-slot 0 :format :float3 :offset 0}
                            {:location 1 :buffer-slot 0 :format :float3 :offset 12}]}
     :rasterizer-state {:fill-mode :fill :cull-mode :back :front-face :counter-clockwise}
     :multisample-state {:sample-count 1}
     :depth-stencil-state {:compare-op :less :enable-depth-test true :enable-depth-write true
                           :back-stencil-state {...} :front-stencil-state {...}}
     :target-info {:color-target-descriptions [{:format :b8g8r8a8-unorm
                                                :blend-state {:enable-blend true ...}}]
                   :depth-stencil-format :d32-float :has-depth-stencil-target true}
     :props id}

  The counts SDL wants beside each array are filled in from the vectors."
  [dev {:keys [vertex-input-state target-info rasterizer-state multisample-state depth-stencil-state] :as info}]
  (with-open [a (ffi/confined-arena)]
    (let [[vbd nvb] (core/alloc-array a gpu/gpu-vertex-buffer-description
                                      (map #(xf % {:input-rate (fn [v] (en c/gpu-vertex-input-rate v))})
                                           (:vertex-buffer-descriptions vertex-input-state)))
          [va nva] (core/alloc-array a gpu/gpu-vertex-attribute
                                     (map #(xf % {:format (fn [v] (en c/gpu-vertex-element-format v))})
                                          (:vertex-attributes vertex-input-state)))
          [ctd nct] (core/alloc-array a gpu/gpu-color-target-description
                                      (map #(xf % {:format texture-format->int :blend-state blend-state})
                                           (:color-target-descriptions target-info)))
          m (cond-> (xf (dissoc info :vertex-input-state :target-info :rasterizer-state :multisample-state :depth-stencil-state)
                        {:primitive-type #(en c/gpu-primitive-type %)})
              true (assoc :vertex-input-state {:vertex-buffer-descriptions vbd :num-vertex-buffers nvb
                                               :vertex-attributes va :num-vertex-attributes nva})
              true (assoc :target-info (-> (select-keys target-info [:has-depth-stencil-target :depth-stencil-format])
                                           (xf {:depth-stencil-format texture-format->int})
                                           (assoc :color-target-descriptions ctd :num-color-targets nct)))
              rasterizer-state (assoc :rasterizer-state (xf rasterizer-state {:fill-mode #(en c/gpu-fill-mode %)
                                                                              :cull-mode #(en c/gpu-cull-mode %)
                                                                              :front-face #(en c/gpu-front-face %)}))
              multisample-state (assoc :multisample-state (xf multisample-state {:sample-count sample-count}))
              depth-stencil-state (assoc :depth-stencil-state (xf depth-stencil-state {:compare-op #(en c/gpu-compare-op %)
                                                                                       :back-stencil-state stencil-op-state
                                                                                       :front-stencil-state stencil-op-state})))]
      (core/check-ptr "SDL_CreateGPUGraphicsPipeline"
                      (gpu/create-gpu-graphics-pipeline dev (core/alloc-fields a gpu/gpu-graphics-pipeline-create-info m))))))

(defn create-compute-pipeline
  "SDL_CreateGPUComputePipeline: {:code :entrypoint :format :num-samplers
  :num-readonly-storage-textures :num-readonly-storage-buffers
  :num-readwrite-storage-textures :num-readwrite-storage-buffers
  :num-uniform-buffers :threadcount-x :threadcount-y :threadcount-z :props}."
  [dev info]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateGPUComputePipeline"
                    (gpu/create-gpu-compute-pipeline dev (core/alloc-fields a gpu/gpu-compute-pipeline-create-info
                                                                            (code-fields a info))))))

(defsdl release-texture! gpu/release-gpu-texture)
(defsdl release-sampler! gpu/release-gpu-sampler)
(defsdl release-buffer! gpu/release-gpu-buffer)
(defsdl release-transfer-buffer! gpu/release-gpu-transfer-buffer)
(defsdl release-shader! gpu/release-gpu-shader
  :doc "Shaders may be released as soon as the pipelines using them are created.")
(defsdl release-graphics-pipeline! gpu/release-gpu-graphics-pipeline)
(defsdl release-compute-pipeline! gpu/release-gpu-compute-pipeline)
(defsdl set-buffer-name! gpu/set-gpu-buffer-name)
(defsdl set-texture-name! gpu/set-gpu-texture-name)

;; ---------------------------------------------------------------------------
;; command buffers
;; ---------------------------------------------------------------------------

(defsdl acquire-command-buffer gpu/acquire-gpu-command-buffer)
(defsdl submit! gpu/submit-gpu-command-buffer)
(defsdl submit-and-acquire-fence! gpu/submit-gpu-command-buffer-and-acquire-fence
  :doc "Submit, answering a fence to wait on with wait-for-fences!; release-fence! it after.")
(defsdl cancel! gpu/cancel-gpu-command-buffer
  :doc "Drop a command buffer without submitting it; not allowed once a swapchain texture was acquired on it.")
(defsdl insert-debug-label! gpu/insert-gpu-debug-label)
(defsdl push-debug-group! gpu/push-gpu-debug-group)
(defsdl pop-debug-group! gpu/pop-gpu-debug-group)
(defsdl generate-mipmaps! gpu/generate-mipmaps-for-gpu-texture)

(defn- push-uniform [what f cb slot data]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/array->ptr a data)]
      (f cb (int slot) p (int n))))
  nil)

(defn push-vertex-uniform-data!
  "SDL_PushGPUVertexUniformData: `data` a float-array (or any array core/array->ptr
  takes) for uniform slot `slot` of the vertex stage, for the draws after it."
  [cb slot data]
  (push-uniform "SDL_PushGPUVertexUniformData" gpu/push-gpu-vertex-uniform-data cb slot data))

(defn push-fragment-uniform-data! [cb slot data]
  (push-uniform "SDL_PushGPUFragmentUniformData" gpu/push-gpu-fragment-uniform-data cb slot data))

(defn push-compute-uniform-data! [cb slot data]
  (push-uniform "SDL_PushGPUComputeUniformData" gpu/push-gpu-compute-uniform-data cb slot data))

(defn blit-texture!
  "SDL_BlitGPUTexture: {:source region :destination region :load-op :clear-color
  :flip-mode :filter :cycle}; a region is {:texture :mip-level
  :layer-or-depth-plane :x :y :w :h}. Outside any pass."
  [cb info]
  (with-open [a (ffi/confined-arena)]
    (gpu/blit-gpu-texture cb (core/alloc-fields a gpu/gpu-blit-info
                                                (xf info {:load-op #(en c/gpu-load-op %)
                                                          :clear-color color
                                                          :flip-mode #(fl c/flip-mode %)
                                                          :filter #(en c/gpu-filter %)}))))
  nil)

;; ---------------------------------------------------------------------------
;; fences
;; ---------------------------------------------------------------------------

(defsdl wait-for-idle! gpu/wait-for-gpu-idle)
(defsdl fence-signaled? gpu/query-gpu-fence :pred true)
(defsdl release-fence! gpu/release-gpu-fence)

(defn wait-for-fences!
  "SDL_WaitForGPUFences: block until all (or, with `all?` false, any) of `fences` signal."
  ([dev fences] (wait-for-fences! dev true fences))
  ([dev all? fences]
   (with-open [a (ffi/confined-arena)]
     (let [[p n] (core/alloc-pointers a fences)]
       (core/check-bool "SDL_WaitForGPUFences" (gpu/wait-for-gpu-fences dev (boolean all?) p n))))
   nil))

;; ---------------------------------------------------------------------------
;; render passes
;; ---------------------------------------------------------------------------

(defn begin-render-pass
  "SDL_BeginGPURenderPass. `color-targets` is a vector of
  {:texture :clear-color [r g b a] :load-op :clear|:load|:dont-care
   :store-op :store|:dont-care|:resolve|:resolve-and-store :cycle
   :mip-level :layer-or-depth-plane :resolve-texture ...};
  `depth-stencil` (optional) {:texture :clear-depth 1.0 :load-op :store-op
  :stencil-load-op :stencil-store-op :clear-stencil :cycle}."
  ([cb color-targets] (begin-render-pass cb color-targets nil))
  ([cb color-targets depth-stencil]
   (with-open [a (ffi/confined-arena)]
     (let [[ct n] (core/alloc-array a gpu/gpu-color-target-info
                                    (map #(xf % {:clear-color color
                                                 :load-op (fn [v] (en c/gpu-load-op v))
                                                 :store-op (fn [v] (en c/gpu-store-op v))})
                                         color-targets))
           ds (if depth-stencil
                (core/alloc-fields a gpu/gpu-depth-stencil-target-info
                                   (xf depth-stencil {:load-op #(en c/gpu-load-op %) :store-op #(en c/gpu-store-op %)
                                                      :stencil-load-op #(en c/gpu-load-op %)
                                                      :stencil-store-op #(en c/gpu-store-op %)}))
                ffi/null)]
       (core/check-ptr "SDL_BeginGPURenderPass" (gpu/begin-gpu-render-pass cb ct n ds))))))

(defsdl end-render-pass! gpu/end-gpu-render-pass)
(defsdl bind-graphics-pipeline! gpu/bind-gpu-graphics-pipeline)

(defn set-viewport!
  "SDL_SetGPUViewport: {:x :y :w :h :min-depth 0.0 :max-depth 1.0}."
  [pass vp]
  (with-open [a (ffi/confined-arena)]
    (gpu/set-gpu-viewport pass (core/alloc-fields a gpu/gpu-viewport (merge {:max-depth 1.0} vp))))
  nil)

(defn set-scissor!
  "SDL_SetGPUScissor to an SDL_Rect map / [x y w h]."
  [pass r]
  (with-open [a (ffi/confined-arena)]
    (gpu/set-gpu-scissor pass (rect/alloc-rect a r)))
  nil)

(defn set-blend-constants!
  "SDL_SetGPUBlendConstants: the color :constant-color blend factors use."
  [pass c]
  (with-open [a (ffi/confined-arena)]
    (gpu/set-gpu-blend-constants pass (core/alloc-fields a pixels/fcolor (color c))))
  nil)

(defn set-stencil-reference! [pass ref]
  (gpu/set-gpu-stencil-reference pass (int ref))
  nil)

(defn bind-vertex-buffers!
  "SDL_BindGPUVertexBuffers: `bindings` [{:buffer :offset} ...] from slot `first-slot`."
  [pass first-slot bindings]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/alloc-array a gpu/gpu-buffer-binding bindings)]
      (gpu/bind-gpu-vertex-buffers pass (int first-slot) p n)))
  nil)

(defn bind-index-buffer!
  "SDL_BindGPUIndexBuffer: {:buffer :offset} of 16- or 32-bit indices."
  [pass binding element-size]
  (with-open [a (ffi/confined-arena)]
    (gpu/bind-gpu-index-buffer pass (core/alloc-fields a gpu/gpu-buffer-binding binding) (index-size element-size)))
  nil)

(defn- bind-samplers [f pass first-slot bindings]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/alloc-array a gpu/gpu-texture-sampler-binding bindings)]
      (f pass (int first-slot) p n)))
  nil)

(defn- bind-pointers [f pass first-slot ptrs]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/alloc-pointers a ptrs)]
      (f pass (int first-slot) p n)))
  nil)

(defn bind-vertex-samplers! "`bindings` [{:texture :sampler} ...]." [pass first-slot bindings] (bind-samplers gpu/bind-gpu-vertex-samplers pass first-slot bindings))
(defn bind-fragment-samplers! "`bindings` [{:texture :sampler} ...]." [pass first-slot bindings] (bind-samplers gpu/bind-gpu-fragment-samplers pass first-slot bindings))
(defn bind-vertex-storage-textures! [pass first-slot textures] (bind-pointers gpu/bind-gpu-vertex-storage-textures pass first-slot textures))
(defn bind-vertex-storage-buffers! [pass first-slot buffers] (bind-pointers gpu/bind-gpu-vertex-storage-buffers pass first-slot buffers))
(defn bind-fragment-storage-textures! [pass first-slot textures] (bind-pointers gpu/bind-gpu-fragment-storage-textures pass first-slot textures))
(defn bind-fragment-storage-buffers! [pass first-slot buffers] (bind-pointers gpu/bind-gpu-fragment-storage-buffers pass first-slot buffers))

(defn draw-primitives!
  "SDL_DrawGPUPrimitives."
  ([pass num-vertices] (draw-primitives! pass num-vertices 1 0 0))
  ([pass num-vertices num-instances first-vertex first-instance]
   (gpu/draw-gpu-primitives pass (int num-vertices) (int num-instances) (int first-vertex) (int first-instance))
   nil))

(defn draw-indexed-primitives!
  "SDL_DrawGPUIndexedPrimitives."
  ([pass num-indices] (draw-indexed-primitives! pass num-indices 1 0 0 0))
  ([pass num-indices num-instances first-index vertex-offset first-instance]
   (gpu/draw-gpu-indexed-primitives pass (int num-indices) (int num-instances) (int first-index) (int vertex-offset) (int first-instance))
   nil))

(defsdl draw-primitives-indirect! gpu/draw-gpu-primitives-indirect)
(defsdl draw-indexed-primitives-indirect! gpu/draw-gpu-indexed-primitives-indirect)

;; ---------------------------------------------------------------------------
;; compute passes
;; ---------------------------------------------------------------------------

(defn begin-compute-pass
  "SDL_BeginGPUComputePass with the read-write storage it writes:
  `textures` [{:texture :mip-level :layer :cycle} ...], `buffers` [{:buffer :cycle} ...]."
  [cb textures buffers]
  (with-open [a (ffi/confined-arena)]
    (let [[tp tn] (core/alloc-array a gpu/gpu-storage-texture-read-write-binding textures)
          [bp bn] (core/alloc-array a gpu/gpu-storage-buffer-read-write-binding buffers)]
      (core/check-ptr "SDL_BeginGPUComputePass" (gpu/begin-gpu-compute-pass cb tp tn bp bn)))))

(defsdl end-compute-pass! gpu/end-gpu-compute-pass)
(defsdl bind-compute-pipeline! gpu/bind-gpu-compute-pipeline)
(defn bind-compute-samplers! [pass first-slot bindings] (bind-samplers gpu/bind-gpu-compute-samplers pass first-slot bindings))
(defn bind-compute-storage-textures! [pass first-slot textures] (bind-pointers gpu/bind-gpu-compute-storage-textures pass first-slot textures))
(defn bind-compute-storage-buffers! [pass first-slot buffers] (bind-pointers gpu/bind-gpu-compute-storage-buffers pass first-slot buffers))

(defn dispatch!
  "SDL_DispatchGPUCompute."
  ([pass x] (dispatch! pass x 1 1))
  ([pass x y z] (gpu/dispatch-gpu-compute pass (int x) (int y) (int z)) nil))

(defsdl dispatch-indirect! gpu/dispatch-gpu-compute-indirect)

;; ---------------------------------------------------------------------------
;; copy passes and transfer buffers
;; ---------------------------------------------------------------------------

(defsdl begin-copy-pass gpu/begin-gpu-copy-pass)
(defsdl end-copy-pass! gpu/end-gpu-copy-pass)

(defn- in-arena [layout m f]
  (with-open [a (ffi/confined-arena)] (f a (core/alloc-fields a layout m))))

(defn upload-to-texture!
  "SDL_UploadToGPUTexture: `source` {:transfer-buffer :offset :pixels-per-row
  :rows-per-layer} to `destination` {:texture :mip-level :layer :x :y :z :w :h :d}."
  ([pass source destination] (upload-to-texture! pass source destination false))
  ([pass source destination cycle?]
   (with-open [a (ffi/confined-arena)]
     (gpu/upload-to-gpu-texture pass (core/alloc-fields a gpu/gpu-texture-transfer-info source)
                                (core/alloc-fields a gpu/gpu-texture-region (merge {:d 1} destination)) (boolean cycle?)))
   nil))

(defn upload-to-buffer!
  "SDL_UploadToGPUBuffer: `source` {:transfer-buffer :offset} to `destination`
  {:buffer :offset :size}."
  ([pass source destination] (upload-to-buffer! pass source destination false))
  ([pass source destination cycle?]
   (with-open [a (ffi/confined-arena)]
     (gpu/upload-to-gpu-buffer pass (core/alloc-fields a gpu/gpu-transfer-buffer-location source)
                               (core/alloc-fields a gpu/gpu-buffer-region destination) (boolean cycle?)))
   nil))

(defn download-from-texture!
  "SDL_DownloadFromGPUTexture: `source` region to `destination` {:transfer-buffer :offset ...}."
  [pass source destination]
  (with-open [a (ffi/confined-arena)]
    (gpu/download-from-gpu-texture pass (core/alloc-fields a gpu/gpu-texture-region (merge {:d 1} source))
                                   (core/alloc-fields a gpu/gpu-texture-transfer-info destination)))
  nil)

(defn download-from-buffer!
  "SDL_DownloadFromGPUBuffer: `source` {:buffer :offset :size} to `destination` {:transfer-buffer :offset}."
  [pass source destination]
  (with-open [a (ffi/confined-arena)]
    (gpu/download-from-gpu-buffer pass (core/alloc-fields a gpu/gpu-buffer-region source)
                                  (core/alloc-fields a gpu/gpu-transfer-buffer-location destination)))
  nil)

(defn copy-texture-to-texture!
  "SDL_CopyGPUTextureToTexture between {:texture :mip-level :layer :x :y :z} locations."
  ([pass source destination w h] (copy-texture-to-texture! pass source destination w h 1 false))
  ([pass source destination w h d cycle?]
   (with-open [a (ffi/confined-arena)]
     (gpu/copy-gpu-texture-to-texture pass (core/alloc-fields a gpu/gpu-texture-location source)
                                      (core/alloc-fields a gpu/gpu-texture-location destination)
                                      (int w) (int h) (int d) (boolean cycle?)))
   nil))

(defn copy-buffer-to-buffer!
  "SDL_CopyGPUBufferToBuffer between {:buffer :offset} locations."
  ([pass source destination size] (copy-buffer-to-buffer! pass source destination size false))
  ([pass source destination size cycle?]
   (with-open [a (ffi/confined-arena)]
     (gpu/copy-gpu-buffer-to-buffer pass (core/alloc-fields a gpu/gpu-buffer-location source)
                                    (core/alloc-fields a gpu/gpu-buffer-location destination) (int size) (boolean cycle?)))
   nil))

(defn map-transfer-buffer
  "SDL_MapGPUTransferBuffer: a pointer to write into (upload) or read from
  (download) until unmap-transfer-buffer!."
  ([dev tb] (map-transfer-buffer dev tb false))
  ([dev tb cycle?] (core/check-ptr "SDL_MapGPUTransferBuffer" (gpu/map-gpu-transfer-buffer dev tb (boolean cycle?)))))

(defsdl unmap-transfer-buffer! gpu/unmap-gpu-transfer-buffer)

(defn write-transfer-buffer!
  "Map `tb`, copy `data` (an array core/array->ptr takes) in at byte `offset`, and
  unmap. Answers the byte count."
  ([dev tb data] (write-transfer-buffer! dev tb data 0 false))
  ([dev tb data offset cycle?]
   (with-open [a (ffi/confined-arena)]
     (let [[src n] (core/array->ptr a data)
           dst (map-transfer-buffer dev tb cycle?)]
       (try (ffi/copy src (ffi/slice dst offset) n)
            (finally (gpu/unmap-gpu-transfer-buffer dev tb)))
       n))))

(defn read-transfer-buffer
  "Map `tb`, copy `n` bytes out from byte `offset` as a byte-array, and unmap."
  ([dev tb n] (read-transfer-buffer dev tb n 0))
  ([dev tb n offset]
   (let [src (map-transfer-buffer dev tb false)]
     (try (ffi/read-array (ffi/slice src offset) n)
          (finally (gpu/unmap-gpu-transfer-buffer dev tb))))))

;; ---------------------------------------------------------------------------
;; one-off copies
;; ---------------------------------------------------------------------------

(defn- with-copy-pass [dev f]
  (let [cb (acquire-command-buffer dev)
        pass (begin-copy-pass cb)]
    (f pass)
    (end-copy-pass! pass)
    cb))

(defn- submit-and-wait! [dev cb]
  (let [fence (submit-and-acquire-fence! cb)]
    (try (wait-for-fences! dev [fence])
         (finally (release-fence! dev fence)))))

(defn upload!
  "Copy `data` (a byte-, float-, short- or int-array) into GPU `buffer` at byte
  `offset` through a temporary transfer buffer, waiting until it lands."
  ([dev buffer data] (upload! dev buffer data 0))
  ([dev buffer data offset]
   (let [n (with-open [a (ffi/confined-arena)] (second (core/array->ptr a data)))
         tb (create-transfer-buffer dev {:usage :upload :size n})]
     (try
       (write-transfer-buffer! dev tb data)
       (submit-and-wait! dev (with-copy-pass dev #(upload-to-buffer! % {:transfer-buffer tb} {:buffer buffer :offset offset :size n})))
       (finally (release-transfer-buffer! dev tb))))
   nil))

(defn upload-texture!
  "Copy tightly packed pixels `data` into the `w` x `h` region at x, y of layer 0,
  mip 0 of `texture`, waiting until it lands."
  ([dev texture w h data] (upload-texture! dev texture 0 0 w h data))
  ([dev texture x y w h data]
   (let [n (with-open [a (ffi/confined-arena)] (second (core/array->ptr a data)))
         tb (create-transfer-buffer dev {:usage :upload :size n})]
     (try
       (write-transfer-buffer! dev tb data)
       (submit-and-wait! dev (with-copy-pass dev #(upload-to-texture! % {:transfer-buffer tb}
                                                                      {:texture texture :x x :y y :w w :h h})))
       (finally (release-transfer-buffer! dev tb))))
   nil))

(defn download
  "Read `n` bytes of GPU `buffer` from byte `offset`, as a byte-array."
  ([dev buffer n] (download dev buffer n 0))
  ([dev buffer n offset]
   (let [tb (create-transfer-buffer dev {:usage :download :size n})]
     (try
       (submit-and-wait! dev (with-copy-pass dev #(download-from-buffer! % {:buffer buffer :offset offset :size n} {:transfer-buffer tb})))
       (read-transfer-buffer dev tb n)
       (finally (release-transfer-buffer! dev tb))))))

(defn download-texture
  "Read the `w` x `h` region at x, y of `texture` (layer 0, mip 0), whose texels
  are `bytes-per-texel` wide, as a byte-array."
  [dev texture x y w h bytes-per-texel]
  (let [n (* w h bytes-per-texel)
        tb (create-transfer-buffer dev {:usage :download :size n})]
    (try
      (submit-and-wait! dev (with-copy-pass dev #(download-from-texture! % {:texture texture :x x :y y :w w :h h} {:transfer-buffer tb})))
      (read-transfer-buffer dev tb n)
      (finally (release-transfer-buffer! dev tb)))))

;; ---------------------------------------------------------------------------
;; formats
;; ---------------------------------------------------------------------------

(defn texel-block-size "SDL_GPUTextureFormatTexelBlockSize in bytes." [format]
  (gpu/gpu-texture-format-texel-block-size (texture-format->int format)))

(defn texture-format-size
  "SDL_CalculateGPUTextureFormatSize: bytes for a w x h x depth-or-layers texture."
  [format w h depth-or-layers]
  (gpu/calculate-gpu-texture-format-size (texture-format->int format) (int w) (int h) (int depth-or-layers)))

(defn texture-supports-format?
  "SDL_GPUTextureSupportsFormat: can `dev` make a `type` texture of `format` with `usage`?"
  [dev format type usage]
  (gpu/gpu-texture-supports-format dev (texture-format->int format) (tex-type type) (fl c/gpu-texture-usage usage)))

(defn texture-supports-sample-count? [dev format n]
  (gpu/gpu-texture-supports-sample-count dev (texture-format->int format) (sample-count n)))

(defn pixel-format->texture-format
  "SDL_GetGPUTextureFormatFromPixelFormat: an sdl3.consts/pixel-format keyword to
  the matching GPU texture format keyword (:invalid when there is none)."
  [pf]
  (int->texture-format (gpu/get-gpu-texture-format-from-pixel-format (core/enum c/pixel-format pf))))

(defn texture-format->pixel-format [tf]
  (core/unenum c/pixel-format-names (gpu/get-pixel-format-from-gpu-texture-format (texture-format->int tf))))
