# jolt-sdl3

[SDL3](https://libsdl.org) bindings for [Jolt](https://jolt-lang.net) (Clojure on Chez Scheme),
built on `jolt.ffi` — no JVM, no JNI, no C shim.

Two layers:

- **`sdl3.raw.*`** — generated, one namespace per SDL header, one `jolt.ffi/defcfn` per
  exported C function (1255 of the 1265 in SDL 3.4.16) plus an `ffi/layout` for every
  struct and union (120, `SDL_Event` included). Nothing is checked or converted; this is
  the equivalent of [thunderchez](https://github.com/ovenpasta/thunderchez)'s SDL2 layer.
- **`sdl3.*`** — hand-written, idiomatic: failures become exceptions carrying
  `SDL_GetError`, flags and enums are keywords, events decode to maps, rects are maps.
  Covers init, video, render, events, keyboard, mouse, rect, surface, timer, log,
  messagebox, clipboard, filesystem, audio, joystick, gamepad, GPU, IO streams and
  properties. The rest (camera, haptic, sensor, storage, tray, dialogs ...) is
  reachable through `sdl3.raw.*` and is where the idiomatic layer grows next.

```clojure
(ns app.core
  (:require [sdl3.core :as sdl]
            [sdl3.video :as video]
            [sdl3.render :as r]
            [sdl3.events :as ev]))

(defn -main [& _]
  (sdl/with-sdl [:video]
    (let [[win ren] (r/create-window-and-renderer "hello" 640 480 [:resizable])]
      (r/set-vsync! ren true)
      (loop []
        (let [quit? (some #(or (= :quit (:type %))
                               (and (= :key-down (:type %)) (= :escape (:key %))))
                          (ev/poll-all!))]
          (r/set-draw-color! ren 24 32 56)
          (r/clear! ren)
          (r/set-draw-color! ren [240 200 60])
          (r/fill-rect! ren {:x 270 :y 190 :w 100 :h 100})
          (r/present! ren)
          (when-not quit? (recur))))
      (r/destroy-renderer! ren)
      (video/destroy-window! win))))
```

## Install

You need Jolt 0.8.0 or newer and libSDL3 on the loader path (`brew install sdl3` on
macOS, your distro's `libsdl3` on Linux). Then depend on this repository:

```clojure
{:deps {io.github.jbondeson/jolt-sdl3 {:git/url "https://github.com/jbondeson/jolt-sdl3"
                                            :git/sha "..."}}}
;; or, while it lives on disk:
{:deps {io.github.jbondeson/jolt-sdl3 {:local/root "../jolt-sdl3"}}}
```

`deps.edn` here declares `libSDL3` under `:jolt/native`; Jolt loads it before any
`sdl3.*` namespace is required, in your project too. Nothing else to configure.

Tasks (`jolt <task>`): `test` runs the suite headlessly; `hello`, `bounce`, `gpu-clear`
and `tone` run the examples; `gen` regenerates the raw layer from the installed headers.

## Conventions

**Errors.** A C function that answers `bool` for success raises an `ExceptionInfo` when
it answers false; one that answers a pointer raises on NULL. The message is
`"SDL_CreateWindow failed: <SDL_GetError>"` and the data holds `:sdl/fn` and
`:sdl/error`. Predicates and lookups whose false or NULL is an answer, not a failure
(`SDL_PollEvent`, `SDL_GetWindowFromID`), are wrapped as such — `has-event?`,
`window-from-id` answering nil.

**Names.** `SDL_CreateWindowAndRenderer` is `create-window-and-renderer` in
`sdl3.raw.render` and the idiomatic `sdl3.render/create-window-and-renderer`. A
state-changing call ends in `!` (`present!`, `destroy-window!`); a getter drops SDL's
`Get` (`window-size`, `renderer-name`); a predicate ends in `?`.

**Flags and enums** are keywords from `sdl3.consts`, which holds every SDL enum and
`#define` group as a map, computed by compiling the headers:

```clojure
(sdl3.consts/window-flags :resizable)     ;=> 32
(sdl3.consts/event-type-names 0x100)      ;=> :quit
(sdl/flags sdl3.consts/init-flags [:video :audio])
```

An idiomatic function takes a keyword, a collection of keywords, or a plain integer
wherever C takes flags, and answers a set of keywords wherever C answers them.

**Pointers.** A window, renderer, texture or surface is the plain Jolt pointer (an
integer) SDL handed out; free it with the matching `destroy-...!`. Memory SDL tells you
to `SDL_free` is freed for you where the wrapper reads it (`sdl3.clipboard/text`,
`sdl3.video/displays`), or with `sdl3.core/free!` / `take-string` where you hold it.

**Events** decode to maps with the C field names kebab-cased, enumerations as keywords,
flags as sets and C strings read:

```clojure
(ev/poll!)
;=> {:type :key-down :timestamp 123456789 :window-id 1 :which 0
;    :scancode :a :key :a :mod #{:lshift} :raw 0 :down true :repeat false}
;=> {:type :mouse-button-down :window-id 1 :which 0 :button :left :down true :clicks 1 :x 10.0 :y 20.0 ...}
;=> {:type :window-resized :window-id 1 :data1 800 :data2 600 ...}
```

**Rects** are `{:x :y :w :h}` maps or `[x y w h]` vectors. `sdl3.render`'s drawing calls
copy them into a scratch cell per call, so a frame of a few hundred rects allocates
nothing on the Jolt side; the plural calls (`fill-rects!`, `draw-lines!`) also take the
`[pointer count]` pair `sdl3.rect/frects` builds once in an arena, for the hot path.

**Callbacks.** `sdl3.timer/add-timer!`, `sdl3.log/set-output-function!` and the audio
stream callbacks wrap a Clojure fn as a `:collect-safe` C callback, since SDL calls them
from threads Jolt did not start. Keep them short and hand work to the main loop with
`sdl3.events/push-event!` or an atom. Callbacks SDL makes on the calling thread
(`sdl3.io/open-io`, `sdl3.properties/keys`) are plain. The remaining callback-taking
functions (`SDL_AddEventWatch`, `SDL_SetWindowHitTest`, ...) are in the raw layer; wrap
them with `jolt.ffi/callback` the same way.

**Structs as maps.** Create-info structs (the GPU's, virtual joysticks) take a map naming
only the fields you care about; the rest are zero, which SDL reads as the default. Numbers
are coerced to the field's type, nested structs are nested maps, and arrays SDL takes
with a separate count are vectors of maps. `sdl3.core/alloc-fields` and `alloc-array`
do this for any layout in the raw layer.

## Audio, input, GPU, streams and properties

```clojure
(require '[sdl3.audio :as audio] '[sdl3.gamepad :as gp] '[sdl3.gpu :as gpu]
         '[sdl3.io :as io] '[sdl3.properties :as props])

;; audio: streams convert between specs; bind one to a device and put! samples
(let [s (audio/open-device-stream :playback {:format :f32 :channels 1 :freq 48000})]
  (audio/put! s (float-array 48000))            ; or a callback: (open-device-stream d spec f)
  (audio/resume-stream-device! s))
(audio/load-wav "boom.wav")                     ;=> {:spec {:format :s16 :channels 2 :freq 44100} :data #bytes}

;; gamepads: controls named by position, events decoded by sdl3.events
(let [g (gp/open (first (gp/gamepads)))]
  (gp/button? g :south)                         ;=> true
  (gp/axis-normalized g :leftx)                 ;=> -0.25
  (gp/button-label g :south)                    ;=> :cross on a PlayStation pad
  (gp/rumble! g 0 30000 200))

;; GPU: create-infos as maps, keywords for enums
(let [dev (gpu/create-device {:shader-formats [:spirv :msl :dxil]})
      buf (gpu/create-buffer dev {:usage :vertex :size 24})]
  (gpu/upload! dev buf (float-array [-1 -1 3 -1 -1 3]))
  (gpu/create-texture dev {:format :r8g8b8a8-unorm :usage [:sampler] :width 256 :height 256}))

;; IO streams: files, memory, or Clojure functions
(io/with-io [s (io/from-file "save.dat" "wb")]
  (io/write-num! s :u32-le 42))
(io/load-file "level.bin")                      ;=> byte-array

;; properties: typed values, keyword or string names
(props/with-properties [p {:window-create-title-string "hi"
                           :window-create-width-number 640
                           :window-create-height-number 480}]
  (sdl3.video/create-window-with-properties p))
```

- **`sdl3.audio`**: drivers and devices, streams with put/get in any format and get/put
  callbacks, WAV loading, sample conversion and mixing.
- **`sdl3.joystick`**: numbered axes, buttons, hats and balls; rumble and LEDs; virtual
  joysticks for tests and input injection.
- **`sdl3.gamepad`**: named controls and state snapshots, button labels, mappings,
  touchpads, sensors, rumble.
- **`sdl3.gpu`**: devices, swapchains, every resource and pipeline, render, compute and
  copy passes, fences, plus `upload!`, `upload-texture!`, `download` and
  `download-texture` for one-off copies. Shaders are not compiled here; hand
  `create-shader` SPIR-V, MSL, DXIL or DXBC.
- **`sdl3.io`**: file and memory streams, typed little/big-endian numbers, whole-file
  load and save, and `open-io` for streams backed by Clojure functions.
- **`sdl3.properties`**: groups as maps in and out, typed `put!` and `get`.

The headless suite drives joysticks and gamepads through virtual devices, audio through
SDL's dummy driver, and on Metal compiles and runs an MSL shader pipeline.

## The raw layer

```clojure
(require '[sdl3.raw.video :as video] '[jolt.ffi :as ffi])

(meta #'video/create-window)
;=> {:doc "SDL_Window * SDL_CreateWindow(const char * title, int w, int h, SDL_WindowFlags flags)"
;    :arglists ([title w h flags]) :sdl/c "SDL_CreateWindow"
;    :sdl/args [:string :int :int :uint64] :sdl/ret :pointer ...}

(ffi/layout-size sdl3.raw.events/keyboard-event)   ;=> 40
(ffi/read p sdl3.raw.events/keyboard-event)         ;=> {:type 768 :scancode 4 :key 97 :mod 1 :down true ...}
```

Type mapping: `bool` → `:bool`, `int`/`Uint32`/`float`... → the matching keyword,
`const char *` → `:string` (copied in, decoded out), any other pointer → `:pointer`,
enums → `:int`, `SDL_GUID` and other by-value structs → `[:by-value ...]`. A `char *`
result (memory you must `SDL_free`) stays a `:pointer`. Functions that wait
(`SDL_WaitEvent`, `SDL_Delay`, ...) are `:blocking`, so the collector is not pinned
while they do. Variadic functions bind with a bare `:&`: `(log/log "%s" msg)`.

Two things to know about calling raw bindings directly:

- A `:float` or `:double` argument rejects an integer — pass `1.0`, not `1`. The
  idiomatic wrappers coerce for you.
- A binding for a function the loaded libSDL3 lacks (an older SDL3 than the headers
  the code was generated from) loads fine and raises only when called.

Not bound: the 7 `va_list` variants (`SDL_LogMessageV`, `SDL_vsnprintf`, ...) and the
3 `SDL_Vulkan_*` functions taking Vulkan handle types.

## Regenerating

`tools/gen.clj` is a Jolt script. It reads `SDL3/SDL_*.h` from the include dir
(`/opt/homebrew/include`, `/usr/local/include` or `/usr/include`, or the one you pass),
parses the `extern SDL_DECLSPEC ... SDLCALL name(...)` declarations, typedefs, enums,
structs and `#define` groups directly — SDL3's headers are regular enough not to need
c2ffi — and compiles one C program with `clang` to get every constant's value and every
struct's `sizeof`/`offsetof`. It writes:

| Output | Contents |
| --- | --- |
| `src/sdl3/raw/<header>.clj` | the bindings and layouts for that header |
| `src/sdl3/consts.clj` | enum and flag maps, hint and property strings |
| `src/sdl3/raw/abi.clj` | the C compiler's sizes and offsets |
| `test/sdl3/abi_test.clj` | asserts every layout matches them |

```
jolt gen          # or: jolt tools/gen.clj /path/to/include .
jolt test
```

It prints what it skipped and why. After an SDL release, regenerate, run the tests and
review the diff; new functions appear in the raw layer with no further work.

## Layout

```
deps.edn            :jolt/native libSDL3, tasks
tools/gen.clj       the generator
src/sdl3/core.clj   errors, defsdl, flags, init/quit, hints   (start here)
src/sdl3/{video,render,events,keyboard,mouse,rect,surface,timer,log,messagebox,clipboard,filesystem}.clj
src/sdl3/{audio,joystick,gamepad,gpu,io,properties}.clj
src/sdl3/consts.clj (generated)
src/sdl3/raw/       (generated)
examples/           hello, bounce, gpu-clear, tone
test/sdl3/          headless suite; test_runner.clj is the -main
```

## Status

- Generated from SDL 3.4.16; tested on macOS arm64 with Jolt 0.8.10. Linux should work
  as-is (the `:jolt/native` entry names `libSDL3.so.0`); Windows is declared but untested.
- Extending the idiomatic layer is mostly `(core/defsdl name raw/name)` lines — see any
  of the `sdl3.*` namespaces for the pattern, and `sdl3.core/defsdl` for the options.
- Licensed under the zlib license (see LICENSE), the same as SDL; the generated files
  carry SDL's notice there.
