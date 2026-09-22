(ns sdl3.camera
  "Cameras (webcams, phone cameras): SDL_camera.h. Needs the :camera subsystem.

  Opening a camera may ask the user for permission; until they answer, the
  camera delivers no frames and permission-state is :pending, then SDL sends a
  :camera-device-approved or :camera-device-denied event. Frames are
  SDL_Surface pointers (read them with sdl3.surface or upload them with
  sdl3.render/create-texture-from-surface) and must be released:

      (let [cam (open (first (cameras)))]
        (loop []
          (with-frame [f cam]
            (when f (draw! (:surface f))))
          ...))

  A spec is {:format :yuy2 :colorspace ... :width 1280 :height 720
  :framerate-numerator 30 :framerate-denominator 1}."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.camera :as camera]))

(defn- read-spec [p]
  (-> (ffi/read p camera/camera-spec)
      (update :format #(core/unenum c/pixel-format-names %))
      (update :colorspace #(core/unenum c/colorspace-names %))))

(defn- spec->fields [spec]
  (cond-> spec
    (keyword? (:format spec)) (update :format #(core/enum c/pixel-format %))
    (keyword? (:colorspace spec)) (update :colorspace #(core/enum c/colorspace %))))

(defn drivers "The camera drivers built into SDL." [] (mapv camera/get-camera-driver (range (camera/get-num-camera-drivers))))
(defsdl current-driver camera/get-current-camera-driver :nullable true)

(defn cameras "SDL_GetCameras: the connected cameras' instance ids." [] (core/with-count camera/get-cameras))
(defsdl camera-name camera/get-camera-name)

(defn position
  "SDL_GetCameraPosition: :front-facing, :back-facing or :unknown (desktop webcams)."
  [id]
  (core/unenum c/camera-position-names (camera/get-camera-position id)))

(defn supported-formats
  "SDL_GetCameraSupportedFormats: every spec camera `id` can deliver, as maps."
  [id]
  (with-outs [n :int]
    (let [p (camera/get-camera-supported-formats id n)]
      (if (ffi/null? p)
        []
        (let [specs (mapv (fn [i] (read-spec (ffi/read p :pointer (* i (ffi/sizeof :pointer)))))
                          (range (ffi/read n :int)))]
          (core/free! p)
          specs)))))

(defn open
  "SDL_OpenCamera. `spec` (optional) is a spec map to ask for; SDL converts to it
  if the camera cannot deliver it natively."
  ([id] (open id nil))
  ([id spec]
   (with-open [a (ffi/confined-arena)]
     (core/check-ptr "SDL_OpenCamera"
                     (camera/open-camera id (if spec (core/alloc-fields a camera/camera-spec (spec->fields spec)) ffi/null))))))

(defsdl close! camera/close-camera)
(defsdl camera-id camera/get-camera-id)
(defsdl properties camera/get-camera-properties)

(defn permission-state
  "SDL_GetCameraPermissionState: :approved, :denied or :pending."
  [cam]
  (core/unenum c/camera-permission-state-names (camera/get-camera-permission-state cam)))

(defn camera-format
  "SDL_GetCameraFormat: the spec frames arrive in."
  [cam]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a camera/camera-spec)]
      (core/check-bool "SDL_GetCameraFormat" (camera/get-camera-format cam p))
      (read-spec p))))

(defn acquire-frame
  "SDL_AcquireCameraFrame: {:surface SDL_Surface* :timestamp-ns n}, or nil when no
  new frame is ready (or permission is pending). release-frame! it soon: the
  camera has only a few buffers."
  [cam]
  (with-outs [ts :uint64]
    (let [s (camera/acquire-camera-frame cam ts)]
      (when-not (ffi/null? s)
        {:surface s :timestamp-ns (ffi/read ts :uint64)}))))

(defn release-frame!
  "SDL_ReleaseCameraFrame for a frame from acquire-frame (or its :surface)."
  [cam frame]
  (camera/release-camera-frame cam (if (map? frame) (:surface frame) frame))
  nil)

(defmacro with-frame
  "Bind the next frame (or nil) for the body and release it after."
  [[sym cam] & body]
  `(let [cam# ~cam
         ~sym (acquire-frame cam#)]
     (try ~@body
          (finally (when ~sym (release-frame! cam# ~sym))))))
