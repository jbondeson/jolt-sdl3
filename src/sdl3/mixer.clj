(ns sdl3.mixer
  "Mixing and decoding: SDL_mixer (SDL3_mixer). Needs libSDL3_mixer installed
  (`brew install sdl3_mixer`); it is an optional native, so check available?
  before relying on it.

  SDL_mixer 3 has three pieces:

  - a **mixer**, which owns an audio device (create-mixer-device) or renders into
    memory (create-mixer, then generate);
  - **audio**, a decoded or decodable sound: WAV, MP3, Ogg Vorbis, FLAC, Opus,
    MOD and MIDI, depending on the build (decoders lists them);
  - **tracks**, which play audio (or a stream) on a mixer, with their own gain,
    position, stereo or 3D placement, fades and loops. Tags group tracks by name.

      (mixer/init!)
      (let [m (mixer/create-mixer-device)
            music (mixer/load-audio m \"theme.ogg\")
            t (mixer/create-track m)]
        (mixer/set-track-audio! t music)
        (mixer/play! t {:loops -1 :fade-in-ms 2000})        ; -1 loops forever
        (mixer/play-audio! m (mixer/load-audio m \"boom.wav\"))) ; fire and forget

  Times are milliseconds unless a name says frames. A spec is the same map
  sdl3.audio uses: {:format :f32 :channels 2 :freq 48000}."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.consts :as sc]
            [sdl3.consts.mixer :as c]
            [sdl3.io :as io]
            [sdl3.properties :as props]
            [sdl3.raw.audio :as raw-audio]
            [sdl3.raw.mixer :as mix]))

(def ^:private availability
  (delay (try (mix/version) true (catch Throwable _ false))))

(defn available?
  "Is libSDL3_mixer installed and loaded? Every other function throws when it isn't."
  []
  @availability)

(defn version
  "The linked SDL_mixer's version: {:major :minor :micro :string}."
  []
  (let [v (mix/version) major (quot v 1000000) minor (mod (quot v 1000) 1000) micro (mod v 1000)]
    {:major major :minor minor :micro micro :string (str major "." minor "." micro)}))

(defn init!
  "MIX_Init. Pair with quit!; calls nest."
  []
  (core/check-bool "MIX_Init" (mix/init))
  nil)

(defsdl quit! mix/quit)

(defmacro with-mixer-library
  "Run body between init! and quit!."
  [& body]
  `(do (init!) (try ~@body (finally (quit!)))))

(defn decoders
  "The audio decoders this SDL_mixer was built with, e.g. [\"WAV\" \"VORBIS\" \"MP3\" \"FLAC\" ...]."
  []
  (mapv mix/get-audio-decoder (range (mix/get-num-audio-decoders))))

;; ---------------------------------------------------------------------------
;; specs
;; ---------------------------------------------------------------------------

(def ^:private format-names
  (merge (into {} (map (fn [[k v]] [v k])) sc/audio-format)
         {(sc/audio-format :s16) :s16 (sc/audio-format :s32) :s32 (sc/audio-format :f32) :f32}))

(defn- alloc-spec [arena spec]
  (if spec
    (let [{:keys [format channels freq]} spec
          p (ffi/alloc arena raw-audio/audio-spec)]
      (ffi/write p raw-audio/audio-spec {:format (core/enum sc/audio-format format)
                                         :channels (int channels) :freq (int freq)})
      p)
    ffi/null))

(defn- read-spec [p]
  (update (ffi/read p raw-audio/audio-spec) :format #(get format-names % %)))

(defn- device-id [d]
  (if (keyword? d) (core/enum sc/audio-device-default d) d))

;; ---------------------------------------------------------------------------
;; mixers
;; ---------------------------------------------------------------------------

(defn create-mixer-device
  "MIX_CreateMixerDevice: a mixer playing on audio device `device` (default the
  default playback device) in `spec` (nil: the device's own). Needs :audio
  initialized (sdl3.core/init! :audio)."
  ([] (create-mixer-device :playback nil))
  ([device] (create-mixer-device device nil))
  ([device spec]
   (with-open [a (ffi/confined-arena)]
     (core/check-ptr "MIX_CreateMixerDevice" (mix/create-mixer-device (device-id device) (alloc-spec a spec))))))

(defn create-mixer
  "MIX_CreateMixer: a mixer with no device, producing `spec` audio on demand with
  generate — for offline rendering, recording, or feeding your own stream."
  [spec]
  (with-open [a (ffi/confined-arena)]
    (core/check-ptr "MIX_CreateMixer" (mix/create-mixer (alloc-spec a spec)))))

(defsdl destroy-mixer! mix/destroy-mixer
  :doc "Stop and destroy the mixer and every track on it.")
(defsdl mixer-properties mix/get-mixer-properties)
(defsdl lock! mix/lock-mixer)
(defsdl unlock! mix/unlock-mixer)

(defmacro with-lock
  "Hold the mixer's lock for the body, so several changes take effect in the same mix."
  [[m] & body]
  `(let [m# ~m]
     (mix/lock-mixer m#)
     (try ~@body (finally (mix/unlock-mixer m#)))))

(defn mixer-format
  "MIX_GetMixerFormat: the spec the mixer mixes in."
  [m]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a raw-audio/audio-spec)]
      (core/check-bool "MIX_GetMixerFormat" (mix/get-mixer-format m p))
      (read-spec p))))

(defsdl gain mix/get-mixer-gain)
(defsdl set-gain! mix/set-mixer-gain
  :doc "Master gain: 1.0 unchanged, 0.0 silent.")
(defsdl frequency-ratio mix/get-mixer-frequency-ratio)
(defsdl set-frequency-ratio! mix/set-mixer-frequency-ratio
  :doc "Speed everything up (> 1.0) or down (< 1.0), shifting pitch.")

(defn generate
  "MIX_Generate on a device-less mixer: the next `n` bytes of mixed audio in the
  mixer's format, as a byte-array."
  [m n]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (max 1 n))
          got (mix/generate m p (int n))]
      (when (neg? got) (throw (core/sdl-error "MIX_Generate")))
      (ffi/read-array p got))))

(defn generate-floats
  "generate for an :f32 mixer, as a float-array of `n` samples (all channels interleaved)."
  [m n]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 4 (max 1 n)))
          got (mix/generate m p (int (* 4 n)))]
      (when (neg? got) (throw (core/sdl-error "MIX_Generate")))
      (ffi/read-array p :float (quot got 4)))))

;; ---------------------------------------------------------------------------
;; audio
;; ---------------------------------------------------------------------------

(defn load-audio
  "MIX_LoadAudio: a sound from a file. With `:predecode true` it is decoded to PCM
  now (best for short effects played often); otherwise it decodes as it plays
  (best for music)."
  ([m path] (load-audio m path {}))
  ([m path {:keys [predecode]}]
   (core/check-ptr "MIX_LoadAudio" (mix/load-audio m (str path) (boolean predecode)))))

(defn load-audio-io
  "MIX_LoadAudio_IO from an sdl3.io stream, closing it when `close?`."
  ([m stream close?] (load-audio-io m stream close? {}))
  ([m stream close? {:keys [predecode]}]
   (core/check-ptr "MIX_LoadAudio_IO" (mix/load-audio-io m stream (boolean predecode) (boolean close?)))))

(defn load-audio-bytes
  "A sound from the encoded file bytes in byte-array `bs` (a WAV, Ogg ... already in memory)."
  ([m bs] (load-audio-bytes m bs {}))
  ([m bs opts] (load-audio-io m (io/from-bytes bs) true opts)))

(defn load-raw-audio
  "MIX_LoadRawAudio: a sound from raw PCM samples in `spec` — a byte-, float- or
  short-array, copied."
  [m data spec]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/array->ptr a data)]
      (core/check-ptr "MIX_LoadRawAudio" (mix/load-raw-audio m p n (alloc-spec a spec))))))

(defn sine-wave
  "MIX_CreateSineWaveAudio: a generated tone at `hz` and `amplitude` (0.0-1.0),
  `ms` long (-1: endless). Handy for tests and placeholders."
  [m hz amplitude ms]
  (core/check-ptr "MIX_CreateSineWaveAudio" (mix/create-sine-wave-audio m (int hz) (double amplitude) (long ms))))

(defsdl destroy-audio! mix/destroy-audio
  :doc "Release a sound; tracks still playing it keep it alive until they finish.")
(defsdl audio-properties mix/get-audio-properties)

(defn audio-format
  "MIX_GetAudioFormat: the sound's native spec."
  [audio]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a raw-audio/audio-spec)]
      (core/check-bool "MIX_GetAudioFormat" (mix/get-audio-format audio p))
      (read-spec p))))

(defn duration
  "The sound's length in milliseconds, or :infinite or :unknown."
  [audio]
  (let [frames (mix/get-audio-duration audio)]
    (cond (= frames (c/duration :infinite)) :infinite
          (= frames (c/duration :unknown)) :unknown
          :else (mix/audio-frames-to-ms audio frames))))

(defn duration-frames
  "MIX_GetAudioDuration in sample frames, or :infinite or :unknown."
  [audio]
  (let [frames (mix/get-audio-duration audio)]
    (get {(c/duration :infinite) :infinite (c/duration :unknown) :unknown} frames frames)))

(defn metadata
  "The tags SDL_mixer read from the sound: :title :artist :album :copyright :track
  :total-tracks :year, where present."
  [audio]
  (let [p (mix/get-audio-properties audio)]
    (into {}
          (for [[k prop] {:title :metadata-title-string :artist :metadata-artist-string
                          :album :metadata-album-string :copyright :metadata-copyright-string
                          :track :metadata-track-number :total-tracks :metadata-total-tracks-number
                          :year :metadata-year-number}
                :let [v (props/get p (c/prop prop))]
                :when (some? v)]
            [k v]))))

(defsdl play-audio! mix/play-audio
  :doc "Play a sound once on a temporary track: fire and forget.")

;; ---------------------------------------------------------------------------
;; tracks
;; ---------------------------------------------------------------------------

;; track -> the arena holding its callbacks, released by destroy-track!
(def ^:private track-arenas (atom {}))

(defn- track-arena [t]
  (or (get @track-arenas t)
      (let [a (ffi/shared-arena)] (swap! track-arenas assoc t a) a)))

(defsdl create-track mix/create-track)

(defn destroy-track!
  "MIX_DestroyTrack, releasing any callbacks set on it."
  [t]
  (mix/destroy-track t)
  (when-let [a (get @track-arenas t)]
    (swap! track-arenas dissoc t)
    (ffi/close-arena a))
  nil)

(defsdl track-properties mix/get-track-properties)
(defsdl track-mixer mix/get-track-mixer)
(defsdl set-track-audio! mix/set-track-audio
  :doc "What the track plays: a sound from load-audio, or nil for nothing.")
(defsdl track-audio mix/get-track-audio :nullable true)
(defsdl set-track-audio-stream! mix/set-track-audio-stream
  :doc "Play from an sdl3.audio stream you keep feeding instead of a sound.")
(defsdl track-audio-stream mix/get-track-audio-stream :nullable true)

(defn set-track-io-stream!
  "MIX_SetTrackIOStream: play an encoded file streamed from an sdl3.io stream."
  [t stream close?]
  (core/check-bool "MIX_SetTrackIOStream" (mix/set-track-io-stream t stream (boolean close?)))
  nil)

(defn- ms->frames [t ms] (mix/track-ms-to-frames t (long ms)))
(defn- frames->ms [t frames] (mix/track-frames-to-ms t (long frames)))

(defn- play-options
  "A properties group for MIX_PlayTrack / MIX_PlayTag from an options map."
  [{:keys [loops fade-in-ms fade-in-start-gain start-ms max-ms loop-start-ms
           append-silence-ms halt-when-exhausted]}]
  (cond-> {}
    loops (assoc :play-loops-number loops)
    fade-in-ms (assoc :play-fade-in-milliseconds-number fade-in-ms)
    fade-in-start-gain (assoc :play-fade-in-start-gain-float (double fade-in-start-gain))
    start-ms (assoc :play-start-millisecond-number start-ms)
    max-ms (assoc :play-max-milliseconds-number max-ms)
    loop-start-ms (assoc :play-loop-start-millisecond-number loop-start-ms)
    append-silence-ms (assoc :play-append-silence-milliseconds-number append-silence-ms)
    (some? halt-when-exhausted) (assoc :play-halt-when-exhausted-boolean (boolean halt-when-exhausted))))

(defn- with-play-options [opts f]
  (let [m (play-options opts)]
    (if (empty? m)
      (f 0)
      (props/with-properties [p (into {} (map (fn [[k v]] [(c/prop k) v])) m)]
        (f p)))))

(defn play!
  "MIX_PlayTrack: start the track from the top. Options:

    :loops              extra times to play after the first (-1: forever)
    :fade-in-ms         fade in over this long
    :fade-in-start-gain the gain the fade starts from (default 0.0)
    :start-ms           start this far in
    :max-ms             stop after this long
    :loop-start-ms      where each loop restarts
    :append-silence-ms  silence to add at the end
    :halt-when-exhausted  stop when the input runs out (for streams)"
  ([t] (play! t {}))
  ([t opts]
   (with-play-options opts #(core/check-bool "MIX_PlayTrack" (mix/play-track t %)))
   nil))

(defn stop!
  "MIX_StopTrack, fading out over `fade-ms` (default 0: at once)."
  ([t] (stop! t 0))
  ([t fade-ms] (core/check-bool "MIX_StopTrack" (mix/stop-track t (ms->frames t fade-ms))) nil))

(defsdl pause! mix/pause-track)
(defsdl resume! mix/resume-track)
(defsdl playing? mix/track-playing :pred true)
(defsdl paused? mix/track-paused :pred true)
(defsdl track-gain mix/get-track-gain)
(defsdl set-track-gain! mix/set-track-gain)
(defsdl track-frequency-ratio mix/get-track-frequency-ratio)
(defsdl set-track-frequency-ratio! mix/set-track-frequency-ratio)
(defsdl loops mix/get-track-loops)
(defsdl set-loops! mix/set-track-loops
  :doc "Extra times to play after the current pass (-1: forever).")

(defn position
  "The track's playback position in milliseconds."
  [t]
  (let [f (mix/get-track-playback-position t)]
    (when (neg? f) (throw (core/sdl-error "MIX_GetTrackPlaybackPosition")))
    (frames->ms t f)))

(defn seek!
  "MIX_SetTrackPlaybackPosition, to `ms` milliseconds in."
  [t ms]
  (core/check-bool "MIX_SetTrackPlaybackPosition" (mix/set-track-playback-position t (ms->frames t ms)))
  nil)

(defn remaining
  "Milliseconds left to play, or :unknown (a stream, or an endless loop)."
  [t]
  (let [f (mix/get-track-remaining t)] (if (neg? f) :unknown (frames->ms t f))))

(defn fading
  "Milliseconds left in the current fade: positive fading in, negative fading out, 0 not fading."
  [t]
  (frames->ms t (mix/get-track-fade-frames t)))

(defn set-stereo!
  "MIX_SetTrackStereo: {:left :right} gains (0.0-1.0) for a stereo placement, or
  nil to turn it off."
  [t gains]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "MIX_SetTrackStereo"
                     (mix/set-track-stereo t (if gains (core/alloc-fields a mix/stereo-gains gains) ffi/null))))
  nil)

(defn set-3d-position!
  "MIX_SetTrack3DPosition: place the track at {:x :y :z} around the listener
  (right-handed: -z is ahead), or nil to turn 3D off."
  [t pos]
  (with-open [a (ffi/confined-arena)]
    (core/check-bool "MIX_SetTrack3DPosition"
                     (mix/set-track-3d-position t (if pos (core/alloc-fields a mix/point-3d pos) ffi/null))))
  nil)

(defn position-3d [t]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a mix/point-3d)]
      (core/check-bool "MIX_GetTrack3DPosition" (mix/get-track-3d-position t p))
      (ffi/read p mix/point-3d))))

(defn set-output-channel-map!
  "MIX_SetTrackOutputChannelMap: route the track's channels, e.g. [1 0] swaps left and right."
  [t chmap]
  (with-open [a (ffi/confined-arena)]
    (let [[p _] (core/array->ptr a (int-array chmap))]
      (core/check-bool "MIX_SetTrackOutputChannelMap" (mix/set-track-output-channel-map t p (count chmap)))))
  nil)

(defn on-stopped!
  "MIX_SetTrackStoppedCallback: call (f track) when the track stops, from the
  mixer's thread — keep it short. nil removes it."
  [t f]
  (if (nil? f)
    (core/check-bool "MIX_SetTrackStoppedCallback" (mix/set-track-stopped-callback t ffi/null ffi/null))
    (let [cb (ffi/callback (track-arena t) (fn [_ track] (f track) nil) [:pointer :pointer] :void :collect-safe)]
      (core/check-bool "MIX_SetTrackStoppedCallback" (mix/set-track-stopped-callback t cb ffi/null))))
  nil)

;; ---------------------------------------------------------------------------
;; tags: control many tracks by name
;; ---------------------------------------------------------------------------

(defsdl tag! mix/tag-track)
(defsdl untag! mix/untag-track)

(defn tags
  "MIX_GetTrackTags: the track's tags."
  [t]
  (with-outs [n :int]
    (let [p (mix/get-track-tags t n)]
      (if (ffi/null? p) [] (let [ts (core/read-strings p (ffi/read n :int))] (core/free! p) ts)))))

(defn tagged-tracks
  "MIX_GetTaggedTracks: every track on mixer `m` tagged `tag`."
  [m tag]
  (with-outs [n :int]
    (let [p (mix/get-tagged-tracks m tag n)]
      (if (ffi/null? p)
        []
        (let [ts (mapv #(ffi/read p :pointer (* % (ffi/sizeof :pointer))) (range (ffi/read n :int)))]
          (core/free! p)
          ts)))))

(defn play-tag!
  "MIX_PlayTag: play! every track tagged `tag`, with play!'s options."
  ([m tag] (play-tag! m tag {}))
  ([m tag opts]
   (with-play-options opts #(core/check-bool "MIX_PlayTag" (mix/play-tag m tag %)))
   nil))

(defn stop-tag!
  "MIX_StopTag, fading out over `fade-ms`."
  ([m tag] (stop-tag! m tag 0))
  ([m tag fade-ms] (core/check-bool "MIX_StopTag" (mix/stop-tag m tag (long fade-ms))) nil))

(defsdl pause-tag! mix/pause-tag)
(defsdl resume-tag! mix/resume-tag)
(defsdl set-tag-gain! mix/set-tag-gain)

(defn stop-all!
  "MIX_StopAllTracks, fading out over `fade-ms`."
  ([m] (stop-all! m 0))
  ([m fade-ms] (core/check-bool "MIX_StopAllTracks" (mix/stop-all-tracks m (long fade-ms))) nil))

(defsdl pause-all! mix/pause-all-tracks)
(defsdl resume-all! mix/resume-all-tracks)

;; ---------------------------------------------------------------------------
;; groups
;; ---------------------------------------------------------------------------

(defsdl create-group mix/create-group
  :doc "A group of tracks mixed together first, so a post-mix callback can process them as one.")
(defsdl destroy-group! mix/destroy-group)
(defsdl group-mixer mix/get-group-mixer)
(defsdl set-track-group! mix/set-track-group
  :doc "Put the track in a group, or nil to take it out.")

;; ---------------------------------------------------------------------------
;; decoding without a mixer
;; ---------------------------------------------------------------------------

(defn create-decoder
  "MIX_CreateAudioDecoder: decode a file to PCM yourself, a chunk at a time."
  [path]
  (core/check-ptr "MIX_CreateAudioDecoder" (mix/create-audio-decoder (str path) 0)))

(defsdl destroy-decoder! mix/destroy-audio-decoder)

(defn decoder-format [d]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a raw-audio/audio-spec)]
      (core/check-bool "MIX_GetAudioDecoderFormat" (mix/get-audio-decoder-format d p))
      (read-spec p))))

(defn decode
  "MIX_DecodeAudio: up to `n` bytes of PCM converted to `spec`, as a byte-array;
  empty at the end."
  [d n spec]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (max 1 n))
          got (mix/decode-audio d p (int n) (alloc-spec a spec))]
      (when (neg? got) (throw (core/sdl-error "MIX_DecodeAudio")))
      (ffi/read-array p got))))

;; ---------------------------------------------------------------------------
;; conversions
;; ---------------------------------------------------------------------------

(defn ms->frames* "MIX_MSToFrames at `sample-rate`." [sample-rate ms] (mix/ms-to-frames (int sample-rate) (long ms)))
(defn frames->ms* "MIX_FramesToMS at `sample-rate`." [sample-rate frames] (mix/frames-to-ms (int sample-rate) (long frames)))
