(ns sdl3.audio
  "Audio devices and streams: SDL_audio.h.

  SDL3 audio is built on streams. A stream converts between two specs, a spec
  being {:format :f32 :channels 2 :freq 48000} with :format one of :u8 :s8
  :s16 :s32 :f32 (native byte order) or :s16le :s16be :s32le ... :f32be. Bind a
  stream to an open device and whatever you put! into it plays:

      (let [s (open-device-stream :playback {:format :f32 :channels 1 :freq 48000})]
        (put! s (float-array (for [i (range 48000)] (* 0.2 (Math/sin (/ (* i 440 2 Math/PI) 48000))))))
        (resume-stream-device! s))   ; streams from open-device-stream start paused

  Devices are ids: :playback and :recording name the defaults. Samples move as
  jolt arrays — a byte-array for any format, a float-array for :f32, a
  short-array for :s16 — or as a [pointer length] pair."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.audio :as audio]
            [sdl3.raw.stdinc :as stdinc]))

;; ---------------------------------------------------------------------------
;; formats and specs
;; ---------------------------------------------------------------------------

(def ^:private format-names
  ;; the native-order spellings win over the -le/-be ones they alias
  (merge (into {} (map (fn [[k v]] [v k])) c/audio-format)
         {(c/audio-format :s16) :s16 (c/audio-format :s32) :s32 (c/audio-format :f32) :f32
          (c/audio-format :u8) :u8 (c/audio-format :s8) :s8 (c/audio-format :unknown) :unknown}))

(defn format->int [f] (core/enum c/audio-format f))
(defn int->format [v] (get format-names v v))

(defn bytes-per-sample
  "SDL_AUDIO_BYTESIZE: 1 for :u8, 2 for :s16, 4 for :f32 ..."
  [format]
  (quot (bit-and (format->int format) (c/audio-mask :bitsize)) 8))

(defn frame-size
  "SDL_AUDIO_FRAMESIZE: bytes per sample frame of `spec` (every channel)."
  [{:keys [format channels]}]
  (* (bytes-per-sample format) channels))

(defn format-name
  "SDL_GetAudioFormatName, e.g. \"SDL_AUDIO_F32LE\"."
  [format]
  (audio/get-audio-format-name (format->int format)))

(defn silence-value
  "SDL_GetSilenceValueForFormat: the byte value of silence (128 for :u8, else 0)."
  [format]
  (audio/get-silence-value-for-format (format->int format)))

(defn- write-spec! [p {:keys [format channels freq]}]
  (ffi/write p audio/audio-spec {:format (format->int format) :channels (int channels) :freq (int freq)})
  p)

(defn- alloc-spec [arena spec]
  (if spec (write-spec! (ffi/alloc arena audio/audio-spec) spec) ffi/null))

(defn- read-spec [p]
  (update (ffi/read p audio/audio-spec) :format int->format))

(defn- device-id [d]
  (if (keyword? d) (core/enum c/audio-device-default d) d))

;; ---------------------------------------------------------------------------
;; drivers and devices
;; ---------------------------------------------------------------------------

(defn drivers
  "The audio drivers built into SDL, e.g. [\"coreaudio\" \"disk\" \"dummy\"]."
  []
  (mapv audio/get-audio-driver (range (audio/get-num-audio-drivers))))

(defsdl current-driver audio/get-current-audio-driver)

(defn playback-devices "SDL_GetAudioPlaybackDevices: the output devices' ids." [] (core/with-count audio/get-audio-playback-devices))
(defn recording-devices "SDL_GetAudioRecordingDevices: the input devices' ids." [] (core/with-count audio/get-audio-recording-devices))

(defn device-name [d] (core/check-ptr "SDL_GetAudioDeviceName" (audio/get-audio-device-name (device-id d))))

(defn device-format
  "SDL_GetAudioDeviceFormat: {:spec {...} :sample-frames n} — the device's
  preferred format and buffer size."
  [d]
  (with-open [a (ffi/confined-arena)]
    (let [spec (ffi/alloc a audio/audio-spec)]
      (with-outs [frames :int]
        (core/check-bool "SDL_GetAudioDeviceFormat" (audio/get-audio-device-format (device-id d) spec frames))
        {:spec (read-spec spec) :sample-frames (ffi/read frames :int)}))))

(defn open-device
  "SDL_OpenAudioDevice: open `d` (an id, :playback or :recording) and answer the
  new logical device's id. `spec` is a hint, nil to take the device's own."
  ([d] (open-device d nil))
  ([d spec]
   (with-open [a (ffi/confined-arena)]
     (let [id (audio/open-audio-device (device-id d) (alloc-spec a spec))]
       (when (zero? id) (throw (core/sdl-error "SDL_OpenAudioDevice")))
       id))))

(defsdl close-device! audio/close-audio-device)
(defsdl pause-device! audio/pause-audio-device)
(defsdl resume-device! audio/resume-audio-device)
(defsdl device-paused? audio/audio-device-paused :pred true)
(defsdl device-physical? audio/is-audio-device-physical :pred true)
(defsdl device-playback? audio/is-audio-device-playback :pred true)

(defn device-gain [d] (audio/get-audio-device-gain (device-id d)))
(defn set-device-gain! [d gain]
  (core/check-bool "SDL_SetAudioDeviceGain" (audio/set-audio-device-gain (device-id d) (double gain)))
  nil)

;; ---------------------------------------------------------------------------
;; streams
;; ---------------------------------------------------------------------------

;; stream -> the arena holding its callbacks, released by destroy-stream!
(def ^:private stream-arenas (atom {}))

(defn- stream-arena [s]
  (or (get @stream-arenas s)
      (let [a (ffi/shared-arena)] (swap! stream-arenas assoc s a) a)))

(defn create-stream
  "SDL_CreateAudioStream converting `src` spec to `dst` spec. Not bound to a
  device: put! into one side and get from the other, or bind! it."
  [src dst]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "SDL_CreateAudioStream" (audio/create-audio-stream (alloc-spec a src) (alloc-spec a dst)))))

(declare set-get-callback! set-put-callback!)

(defn open-device-stream
  "SDL_OpenAudioDeviceStream: open device `d` with a stream bound to it whose
  app-side format is `spec`, and answer the stream. The device starts PAUSED —
  call resume-stream-device!. With `f`, it is the stream's get callback (on a
  playback device) or put callback (recording); see set-get-callback!.
  destroy-stream! closes the device too."
  ([d spec] (open-device-stream d spec nil))
  ([d spec f]
   (with-open [a (ffi/confined-arena)]
     (let [s (core/check-ptr "SDL_OpenAudioDeviceStream"
                             (audio/open-audio-device-stream (device-id d) (alloc-spec a spec) ffi/null ffi/null))]
       (when f
         (if (= d :recording) (set-put-callback! s f) (set-get-callback! s f)))
       s))))

(defn destroy-stream!
  "SDL_DestroyAudioStream, releasing any callbacks set on it."
  [s]
  (audio/destroy-audio-stream s)
  (when-let [a (get @stream-arenas s)]
    (swap! stream-arenas dissoc s)
    (ffi/close-arena a))
  nil)

(defsdl bind! audio/bind-audio-stream
  :doc "Bind stream to an open logical device id: playback pulls from it, recording feeds it.")
(defsdl unbind! audio/unbind-audio-stream)
(defsdl stream-device audio/get-audio-stream-device)
(defsdl stream-properties audio/get-audio-stream-properties)
(defsdl pause-stream-device! audio/pause-audio-stream-device)
(defsdl resume-stream-device! audio/resume-audio-stream-device)
(defsdl stream-device-paused? audio/audio-stream-device-paused :pred true)
(defsdl lock-stream! audio/lock-audio-stream)
(defsdl unlock-stream! audio/unlock-audio-stream)
(defsdl flush! audio/flush-audio-stream
  :doc "Make everything put! so far available to get, even a partial conversion block (call at end of input).")
(defsdl clear! audio/clear-audio-stream)

(defn stream-format
  "SDL_GetAudioStreamFormat as {:src spec :dst spec}."
  [s]
  (with-open [a (ffi/confined-arena)]
    (let [src (ffi/alloc a audio/audio-spec) dst (ffi/alloc a audio/audio-spec)]
      (core/check-bool "SDL_GetAudioStreamFormat" (audio/get-audio-stream-format s src dst))
      {:src (read-spec src) :dst (read-spec dst)})))

(defn set-stream-format!
  "SDL_SetAudioStreamFormat; nil leaves that side as it is."
  [s src dst]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "SDL_SetAudioStreamFormat" (audio/set-audio-stream-format s (alloc-spec a src) (alloc-spec a dst))))
  nil)

(defsdl stream-gain audio/get-audio-stream-gain)
(defsdl set-stream-gain! audio/set-audio-stream-gain)
(defsdl frequency-ratio audio/get-audio-stream-frequency-ratio)
(defsdl set-frequency-ratio! audio/set-audio-stream-frequency-ratio
  :doc "Play faster (> 1.0) or slower (< 1.0), shifting pitch; 0.01 to 100.")

(defn available "Bytes ready to get." [s] (audio/get-audio-stream-available s))
(defn queued "Bytes put! but not yet consumed." [s] (audio/get-audio-stream-queued s))

(def ^:private as-bytes core/array->ptr)

(defn put!
  "SDL_PutAudioStreamData: queue samples in the stream's source format. SDL copies
  them, so the array is free afterwards."
  [s data]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (as-bytes a data)]
      (core/check-bool "SDL_PutAudioStreamData" (audio/put-audio-stream-data s p (int n)))))
  nil)

(defn put-planar!
  "SDL_PutAudioStreamPlanarData: queue `num-samples` samples per channel from
  separate per-channel arrays, one entry of `planes` per channel in order. An
  entry is an array core/array->ptr takes (a byte-array for :u8/:s8, a
  float-array for :f32 ...) or nil for a silent channel. SDL copies the data."
  [s planes num-samples]
  (with-open [a (ffi/confined-arena)]
    (let [ptrs (mapv (fn [plane] (if (nil? plane) ffi/null (first (as-bytes a plane)))) planes)
          [pp n] (core/alloc-pointers a ptrs)]
      (core/check-bool "SDL_PutAudioStreamPlanarData"
                       (audio/put-audio-stream-planar-data s pp (int n) (int num-samples)))))
  nil)

(defn get-bytes
  "SDL_GetAudioStreamData: up to `n` converted bytes as a byte-array (all that is
  available when n is omitted)."
  ([s] (get-bytes s (available s)))
  ([s n]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (max 1 n))
           got (audio/get-audio-stream-data s p (int n))]
       (when (neg? got) (throw (core/sdl-error "SDL_GetAudioStreamData")))
       (ffi/read-array p got)))))

(defn get-floats
  "get-bytes for an :f32 destination, as a float-array of up to `n` samples."
  ([s] (get-floats s (quot (available s) 4)))
  ([s n]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (max 4 (* 4 n)))
           got (audio/get-audio-stream-data s p (int (* 4 n)))]
       (when (neg? got) (throw (core/sdl-error "SDL_GetAudioStreamData")))
       (ffi/read-array p :float (quot got 4))))))

(defn get-shorts
  "get-bytes for an :s16 destination, as a short-array of up to `n` samples."
  ([s] (get-shorts s (quot (available s) 2)))
  ([s n]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (max 2 (* 2 n)))
           got (audio/get-audio-stream-data s p (int (* 2 n)))]
       (when (neg? got) (throw (core/sdl-error "SDL_GetAudioStreamData")))
       (ffi/read-array p :int16 (quot got 2))))))

(defn set-get-callback!
  "SDL_SetAudioStreamGetCallback: call (f stream additional-bytes total-bytes)
  whenever the device wants more than the stream holds; put! at least
  `additional-bytes` from it. nil removes the callback.

  f runs on SDL's audio thread, which jolt did not start: keep it fast and free
  of blocking, and share state with the main thread through atoms."
  [s f]
  (if (nil? f)
    (core/check-bool "SDL_SetAudioStreamGetCallback" (audio/set-audio-stream-get-callback s ffi/null ffi/null))
    (let [cb (ffi/callback (stream-arena s)
                           (fn [_ stream additional total] (f stream additional total) nil)
                           [:pointer :pointer :int :int] :void :collect-safe)]
      (core/check-bool "SDL_SetAudioStreamGetCallback" (audio/set-audio-stream-get-callback s cb ffi/null))))
  nil)

(defn set-put-callback!
  "SDL_SetAudioStreamPutCallback: call (f stream additional-bytes total-bytes)
  after data was put into the stream (by a recording device, say). Same thread
  rules as set-get-callback!."
  [s f]
  (if (nil? f)
    (core/check-bool "SDL_SetAudioStreamPutCallback" (audio/set-audio-stream-put-callback s ffi/null ffi/null))
    (let [cb (ffi/callback (stream-arena s)
                           (fn [_ stream additional total] (f stream additional total) nil)
                           [:pointer :pointer :int :int] :void :collect-safe)]
      (core/check-bool "SDL_SetAudioStreamPutCallback" (audio/set-audio-stream-put-callback s cb ffi/null))))
  nil)

;; ---------------------------------------------------------------------------
;; WAV and conversion
;; ---------------------------------------------------------------------------

(defn- take-audio [buf-pp len-p]
  (let [p (ffi/read buf-pp :pointer)
        n (ffi/read len-p :uint32)
        bs (ffi/read-array p n)]
    (stdinc/free p)
    bs))

(defn load-wav
  "SDL_LoadWAV: {:spec {...} :data byte-array} of a .wav file."
  [path]
  (with-open [a (ffi/confined-arena)]
    (let [spec (ffi/alloc a audio/audio-spec) pp (ffi/alloc a :pointer) len (ffi/alloc a :uint32)]
      (core/check-bool "SDL_LoadWAV" (audio/load-wav (str path) spec pp len))
      {:spec (read-spec spec) :data (take-audio pp len)})))

(defn load-wav-io
  "SDL_LoadWAV_IO from an sdl3.io stream; closes it when `close?`."
  [s close?]
  (with-open [a (ffi/confined-arena)]
    (let [spec (ffi/alloc a audio/audio-spec) pp (ffi/alloc a :pointer) len (ffi/alloc a :uint32)]
      (core/check-bool "SDL_LoadWAV_IO" (audio/load-wav-io s (boolean close?) spec pp len))
      {:spec (read-spec spec) :data (take-audio pp len)})))

(defn convert
  "SDL_ConvertAudioSamples: the byte-array `data` in spec `src` converted to spec
  `dst`, as a new byte-array. For a one-off; a stream is better for a flow."
  [src data dst]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (as-bytes a data)
          out-pp (ffi/alloc a :pointer)
          out-len (ffi/alloc a :int)]
      (core/check-bool "SDL_ConvertAudioSamples"
                       (audio/convert-audio-samples (alloc-spec a src) p (int n) (alloc-spec a dst) out-pp out-len))
      (let [q (ffi/read out-pp :pointer)
            bs (ffi/read-array q (ffi/read out-len :int))]
        (stdinc/free q)
        bs))))

(defn mix
  "SDL_MixAudio: `src` mixed into `dst` (byte-arrays of `format` samples) at
  `volume` 0.0-1.0, answered as a new byte-array the length of dst."
  [dst src format volume]
  (with-open [a (ffi/confined-arena)]
    (let [[dp dn] (core/bytes->ptr a dst)
          [sp sn] (core/bytes->ptr a src)]
      (core/check-bool "SDL_MixAudio" (audio/mix-audio dp sp (format->int format) (min dn sn) (double volume)))
      (ffi/read-array dp dn))))
