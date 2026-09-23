# jolt-sdl3

[![CI](https://github.com/jbondeson/jolt-sdl3/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/jbondeson/jolt-sdl3/actions/workflows/ci.yml)

[SDL3](https://libsdl.org) bindings for [Jolt](https://jolt-lang.net), Clojure on Chez
Scheme. Everything goes through `jolt.ffi`, so there's no JVM, no JNI and no C shim to
build.

## What is this, and why does it exist?

SDL2 bindings exist for Chez Scheme via [thunderchez](https://github.com/ovenpasta/thunderchez),
but I couldn't find any for SDL3. This library was generated quickly, with AI help (see the
disclosure below), for personal use. Consider the whole library ALPHA. The idiomatic Clojure
API passes its automated tests but hasn't been used in a real project yet, and it will likely
go through a number of changes.

## Overview

The library comes in two layers, and you can mix them freely:

- **`sdl3.*`** is the one you'll usually want. It feels like Clojure: failures throw
  exceptions carrying `SDL_GetError`, flags and enums are keywords, events arrive as maps,
  and rects are maps too. It covers init, video, render, events, keyboard, mouse, rects,
  surfaces, timers, logging, message boxes, the clipboard, the filesystem, audio,
  joysticks, gamepads, haptics, sensors, cameras, the GPU API, IO streams, async IO,
  storage, properties, the system tray, file dialogs, threads and atomics, processes,
  HID, pixel formats, calendar time, touch, and system queries (CPU, locale, power, URLs,
  shared objects).
- **`sdl3.raw.*`** is the whole C API, generated straight from the SDL headers: one
  namespace per header, one `jolt.ffi/defcfn` per exported function (1256 of the 1265 in
  SDL 3.4.16), and an `ffi/layout` for every struct and union (120 of them, `SDL_Event`
  included). Nothing is checked or converted here. It plays the same role as
  [thunderchez](https://github.com/ovenpasta/thunderchez)'s SDL2 bindings do for plain
  Chez. The only headers without an `sdl3.*` counterpart are `SDL_main.h` (C's program
  entry point) and `SDL_stdinc.h` (SDL's own copy of libc).

Here's a window with a yellow square that closes on Escape:

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

## Getting started

You'll need Jolt 0.8.0 or newer, and libSDL3 somewhere your system's loader can find it.
On macOS that's `brew install sdl3`; on Linux, your distro's `libsdl3` package. Then add
the library to your `deps.edn`:

```clojure
{:deps {io.github.jbondeson/jolt-sdl3 {:git/url "https://github.com/jbondeson/jolt-sdl3"
                                       :git/tag "v0.2.0"
                                       :git/sha "7ff326e7861156ac7ab2c0fce7c0bfa3a45e1c49"}}}
;; or, if you have a checkout on disk:
{:deps {io.github.jbondeson/jolt-sdl3 {:local/root "../jolt-sdl3"}}}
```

That's all the setup there is. This library's `deps.edn` declares libSDL3 under
`:jolt/native`, so Jolt loads it for your project before any `sdl3.*` namespace is
required.

A few tasks are included (`jolt <task>`):

- `test` runs the test suite. It's headless, so no windows pop up.
- `sdl-example` runs a port of one of SDL's official examples; see [Examples](#examples).
- `showcase` runs one of this library's own examples; `jolt showcase` lists them.
- `gen` regenerates the raw layer from the SDL headers you have installed.

## Examples

`examples/examples/sdl/` holds Jolt ports of [SDL's official
examples](https://examples.libsdl.org/SDL3/), one namespace each, grouped by SDL's
categories: renderer, audio, input, pen, camera, asyncio, misc and demo. They follow the C
originals closely, comments included, so you can read them side by side. Run one by its
SDL name, with or without SDL's number:

```
jolt sdl-example                       # list them all
jolt sdl-example renderer/primitives
jolt sdl-example demo/01-snake
```

SDL writes these examples against its app-callback model (`SDL_AppInit`, `SDL_AppEvent`,
`SDL_AppIterate` and `SDL_AppQuit`). `examples.sdl.app` runs the same four functions in a
plain loop, so each port keeps the shape of its original.

A few examples load images and sounds from SDL's `test/` folder. Those aren't included in
this repository, since `sample.wav` is a song excerpt SDL distributes by the artist's
permission. The first run of an example that needs one downloads it from SDL's 3.4.16
release into `examples/assets/`, using `curl`.

`examples/examples/showcase/` holds this library's own examples, run with `jolt showcase
<name>`: `hello` (the smallest useful program), `bounce` (the renderer, events and keyboard state), `gpu-clear` (the GPU
API's swapchain loop), `tone` (an audio stream fed by a callback) and `tray` (a tray menu
that opens a file dialog).

## How it fits together

**Errors.** When a C function returns `false` or NULL to signal failure, the wrapper
throws an `ExceptionInfo` instead. The message reads like `"SDL_CreateWindow failed:
<what SDL_GetError said>"`, and the exception's data holds `:sdl/fn` and `:sdl/error`.
Some functions use false or NULL as a normal answer rather than an error, like
`SDL_PollEvent` or `SDL_GetWindowFromID`. Those don't throw: `has-event?` returns a
boolean and `window-from-id` returns nil.

**Names.** `SDL_CreateWindowAndRenderer` becomes `create-window-and-renderer`, both in
`sdl3.raw.render` and in `sdl3.render`. In the `sdl3.*` layer, functions that change
state end in `!` (`present!`, `destroy-window!`), getters drop SDL's `Get`
(`window-size`, `renderer-name`), and predicates end in `?`.

**Flags and enums** are keywords. `sdl3.consts` holds every SDL enum and `#define` group
as a map, with the values taken from compiling the real headers:

```clojure
(sdl3.consts/window-flags :resizable)     ;=> 32
(sdl3.consts/event-type-names 0x100)      ;=> :quit
(sdl/flags sdl3.consts/init-flags [:video :audio])
```

Wherever C takes flags, you can pass a keyword, a collection of keywords, or a plain
integer. Wherever C returns flags, you get back a set of keywords.

**Pointers.** A window, renderer, texture or surface is simply the pointer SDL handed
out, which in Jolt is an integer. Free it with the matching `destroy-...!` function.
When SDL returns memory you're meant to `SDL_free`, the wrappers that read it free it for
you (`sdl3.clipboard/text`, `sdl3.video/displays`). If you're holding such memory
yourself, `sdl3.core/free!` and `take-string` take care of it.

**Events** arrive as maps. Field names are SDL's, kebab-cased, with enumerations as
keywords, flags as sets, and C strings already read:

```clojure
(ev/poll!)
;=> {:type :key-down :timestamp 123456789 :window-id 1 :which 0
;    :scancode :a :key :a :mod #{:lshift} :raw 0 :down true :repeat false}
;=> {:type :mouse-button-down :window-id 1 :which 0 :button :left :down true :clicks 1 :x 10.0 :y 20.0 ...}
;=> {:type :window-resized :window-id 1 :data1 800 :data2 600 ...}
```

**Rects** can be `{:x :y :w :h}` maps or `[x y w h]` vectors. The drawing calls in
`sdl3.render` copy each one into a reusable scratch buffer, so drawing a few hundred
rects a frame doesn't allocate anything on the Jolt side. If you're drawing lots of
rects every frame, the plural calls (`fill-rects!`, `draw-lines!`) also accept the
`[pointer count]` pair that `sdl3.rect/frects` builds once in an arena.

**Callbacks.** SDL calls some callbacks from its own threads: timers
(`sdl3.timer/add-timer!`), log output (`sdl3.log/set-output-function!`) and audio
streams. Those wrappers mark the callback `:collect-safe` for you. Keep these callbacks
short, and hand work back to your main loop with `sdl3.events/push-event!` or an atom.
Callbacks SDL makes on your own thread, like `sdl3.io/open-io` and
`sdl3.properties/keys`, need nothing special. A few callback-taking functions
(`SDL_AddEventWatch`, `SDL_SetWindowHitTest`, ...) are only in the raw layer so far; you
can wrap them with `jolt.ffi/callback` in the same way.

**Structs as maps.** For create-info structs, like the GPU's or a virtual joystick's
description, you pass a map with just the fields you care about. Everything else is
zero, which SDL treats as the default. Numbers are converted to the field's type for you,
nested structs are nested maps, and arrays that SDL pairs with a count are vectors of
maps. If you need this for another struct from the raw layer, `sdl3.core/alloc-fields`
and `alloc-array` do the same thing for any layout.

## Audio, input, GPU, storage and the desktop

A quick tour of what else is in there:

```clojure
(require '[sdl3.audio :as audio] '[sdl3.gamepad :as gp] '[sdl3.gpu :as gpu]
         '[sdl3.io :as io] '[sdl3.properties :as props])

;; audio: streams convert between formats; bind one to a device and put! samples in
(let [s (audio/open-device-stream :playback {:format :f32 :channels 1 :freq 48000})]
  (audio/put! s (float-array 48000))            ; or pass a callback: (open-device-stream d spec f)
  (audio/resume-stream-device! s))
(audio/load-wav "boom.wav")                     ;=> {:spec {:format :s16 :channels 2 :freq 44100} :data #bytes}

;; gamepads: controls are named by position, and events arrive decoded
(let [g (gp/open (first (gp/gamepads)))]
  (gp/button? g :south)                         ;=> true
  (gp/axis-normalized g :leftx)                 ;=> -0.25
  (gp/button-label g :south)                    ;=> :cross on a PlayStation pad
  (gp/rumble! g 0 30000 200))

;; GPU: create-infos are maps, enums are keywords
(let [dev (gpu/create-device {:shader-formats [:spirv :msl :dxil]})
      buf (gpu/create-buffer dev {:usage :vertex :size 24})]
  (gpu/upload! dev buf (float-array [-1 -1 3 -1 -1 3]))
  (gpu/create-texture dev {:format :r8g8b8a8-unorm :usage [:sampler] :width 256 :height 256}))

;; IO streams: files, memory, or your own Clojure functions
(io/with-io [s (io/from-file "save.dat" "wb")]
  (io/write-num! s :u32-le 42))
(io/load-file "level.bin")                      ;=> byte-array

;; properties: typed values, named by keyword or by SDL's string
(props/with-properties [p {:window-create-title-string "hi"
                           :window-create-width-number 640
                           :window-create-height-number 480}]
  (sdl3.video/create-window-with-properties p))
```

- **`sdl3.audio`**: drivers and devices, streams that take and give samples in any
  format (with optional callbacks), WAV loading, sample conversion and mixing.
- **`sdl3.joystick`**: numbered axes, buttons, hats and balls, plus rumble and LEDs. You
  can also create virtual joysticks, which is handy for tests or injecting input.
- **`sdl3.gamepad`**: named controls, state snapshots, button labels, mappings,
  touchpads, sensors and rumble.
- **`sdl3.gpu`**: devices, swapchains, every resource and pipeline type, render, compute
  and copy passes, and fences. For one-off copies there's `upload!`, `upload-texture!`,
  `download` and `download-texture`. Shaders aren't compiled here, so give
  `create-shader` SPIR-V, MSL, DXIL or DXBC.
- **`sdl3.io`**: file and memory streams, little- and big-endian numbers, whole-file load
  and save, and `open-io` for streams backed by your own Clojure functions.
- **`sdl3.properties`**: property groups as maps in and out, with typed `put!` and `get`.
- **`sdl3.haptic`**: force-feedback effects written as maps (`{:type :sine :period 100
  ...}`), simple rumble, gain and autocenter.
- **`sdl3.sensor`**: device accelerometers and gyroscopes.
- **`sdl3.camera`**: cameras, their formats and permission state, and frames as surfaces
  (see `with-frame`).
- **`sdl3.storage`**: title, user and directory containers you can read, write, list,
  glob and inspect.
- **`sdl3.tray`**: tray icons and menus, which you can describe as data with
  `build-menu!`.
- **`sdl3.dialog`**: native open, save and folder dialogs. The answer comes back through
  a callback or a promise; keep pumping events until it arrives.
- **`sdl3.thread`**: SDL threads that run Clojure functions (`wait-thread!` gives you
  their return value, or rethrows their exception), plus mutexes, read-write locks,
  semaphores, conditions and atomics in native memory. These are for sharing with C
  code; for plain Jolt code, Jolt's own threads and locks are the better fit.
- **`sdl3.process`**: child processes with piped or inherited stdio, a custom
  environment and working directory, and `sh` for when you just want the output.
- **`sdl3.asyncio`**: queued async reads, writes and whole-file loads, with tagged
  results.
- **`sdl3.hid`**: raw HID devices: enumeration, reports and device strings.
- **`sdl3.system`**, **`sdl3.time`**, **`sdl3.touch`** and **`sdl3.pixels`** cover CPU
  and memory info, locales, battery, opening URLs and loading shared objects; calendar
  date-times; touch devices and pens; and pixel formats and palettes. Custom blend modes
  live in `sdl3.render/compose-blend-mode`, and the Metal and Vulkan helpers are in
  `sdl3.video`.

The test suite gets a lot done without any special hardware. It drives joysticks and
gamepads through virtual devices, audio through SDL's dummy driver, storage through a
temporary directory, and tray menus through simulated clicks. On Metal it also compiles
and runs a real MSL shader pipeline. Cameras, haptics and sensors are only listed, since
opening them needs hardware or permission. Dialogs need a person to click them, so the
`tray` example is the place to try those out.

## Using the raw layer

Each raw binding carries its C signature and types in its metadata:

```clojure
(require '[sdl3.raw.video :as video] '[jolt.ffi :as ffi])

(meta #'video/create-window)
;=> {:doc "SDL_Window * SDL_CreateWindow(const char * title, int w, int h, SDL_WindowFlags flags)"
;    :arglists ([title w h flags]) :sdl/c "SDL_CreateWindow"
;    :sdl/args [:string :int :int :uint64] :sdl/ret :pointer ...}

(ffi/layout-size sdl3.raw.events/keyboard-event)   ;=> 40
(ffi/read p sdl3.raw.events/keyboard-event)         ;=> {:type 768 :scancode 4 :key 97 :mod 1 :down true ...}
```

C types map to Jolt's FFI types like this:

- `bool` becomes `:bool`, and `int`, `Uint32`, `float` and friends become the matching
  keyword.
- `const char *` becomes `:string`, which is copied in and decoded on the way out. A
  plain `char *` result stays a `:pointer`, because that's memory you need to `SDL_free`.
- Every other pointer is a `:pointer`, and enums are `:int`.
- `SDL_GUID` and other structs passed by value use `[:by-value ...]`.
- Functions that wait, like `SDL_WaitEvent` and `SDL_Delay`, are marked `:blocking`, so
  Jolt's garbage collector can keep running while they wait.
- Variadic functions use a bare `:&`, as in `(log/log "%s" msg)`.

Two things are good to know when you call raw bindings directly:

- A `:float` or `:double` argument won't accept an integer, so pass `1.0` rather than
  `1`. The `sdl3.*` wrappers convert for you.
- If your libSDL3 is older than the headers these bindings came from, a binding for a
  function it lacks still loads fine. It only throws when you call it.

A few functions aren't bound. The 7 `va_list` variants (`SDL_LogMessageV`,
`SDL_vsnprintf`, ...) take an argument type that Jolt has no way to build, and each one
has a `...` version that is bound. `SDL_CreateThread` and `SDL_CreateThreadWithProperties`
exist only as header macros; the `...Runtime` functions they expand to are bound, and
`sdl3.thread` uses those. Vulkan handle types (`VkInstance`, `VkSurfaceKHR`) bind as
`:pointer`, which is their size on 64-bit systems.

## Regenerating the bindings

`tools/gen.clj` is a Jolt script. It reads the `SDL3/SDL_*.h` headers from your include
directory (`/opt/homebrew/include`, `/usr/local/include` or `/usr/include`, or whichever
one you pass). SDL3's headers are regular enough to parse directly, so there's no need
for c2ffi. It picks up the function declarations, typedefs, enums, structs and `#define`
groups, then compiles one small C program with `clang` to get every constant's value and
every struct's size and field offsets. It writes:

| Output | Contents |
| --- | --- |
| `src/sdl3/raw/<header>.clj` | the bindings and layouts for that header |
| `src/sdl3/consts.clj` | enum and flag maps, hint and property strings |
| `src/sdl3/raw/abi.clj` | the C compiler's sizes and offsets |
| `test/sdl3/abi_test.clj` | a test that every layout matches them |

```
jolt gen          # or: jolt tools/gen.clj /path/to/include .
jolt test
```

The generator prints anything it skipped, along with the reason. When a new SDL release
comes out, regenerate, run the tests and look over the diff. New functions show up in
the raw layer without any extra work.

## Project layout

```
deps.edn            :jolt/native libSDL3, tasks
tools/gen.clj       the generator
src/sdl3/core.clj   errors, defsdl, flags, init/quit, hints   (a good place to start reading)
src/sdl3/{video,render,events,keyboard,mouse,rect,surface,timer,log,messagebox,clipboard,filesystem}.clj
src/sdl3/{audio,joystick,gamepad,gpu,io,properties}.clj
src/sdl3/{camera,haptic,sensor,storage,tray,dialog}.clj
src/sdl3/{thread,process,asyncio,hid,system,time,touch,pixels}.clj
src/sdl3/consts.clj (generated)
src/sdl3/raw/       (generated)
examples/examples/sdl/        ports of SDL's official examples, and their harness and runner
examples/examples/showcase/   hello, bounce, gpu-clear, tone, tray, and their runner
test/sdl3/          the headless suite; test_runner.clj is the entry point
```

## Status

- The bindings are generated from SDL 3.4.16 and tested on macOS arm64 with Jolt 0.8.10.
  Linux should work as-is, since the `:jolt/native` entry names `libSDL3.so.0`. Windows
  is declared too, but hasn't been tested yet.
- Adding to the `sdl3.*` layer is mostly a matter of `(core/defsdl name raw/name)` lines.
  Any of the `sdl3.*` namespaces shows the pattern, and `sdl3.core/defsdl` documents the
  options.
- The library is under the zlib license (see LICENSE), the same license as SDL. The
  generated files carry SDL's notice there as well.

## AI use disclosure

This library was written with AI assistance, using Anthropic's Claude Fable 5.1 and
Claude Opus 5.5 through Claude Code. The models wrote the binding generator
(`tools/gen.clj`), the `sdl3.*` namespaces, the tests, the examples and this README. The
raw layer under `src/sdl3/raw/` and `src/sdl3/consts.clj` is produced mechanically by the
generator from the SDL3 headers, and its struct layouts are checked against the C
compiler by `test/sdl3/abi_test.clj`.
