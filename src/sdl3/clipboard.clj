(ns sdl3.clipboard
  "The system clipboard and primary selection: SDL_clipboard.h. Needs the :video
  subsystem."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl with-outs]]
            [sdl3.raw.clipboard :as clipboard]))

(defsdl set-text! clipboard/set-clipboard-text)
(defsdl has-text? clipboard/has-clipboard-text :pred true)

(defn text
  "SDL_GetClipboardText: the clipboard's text, \"\" when it holds none."
  []
  (or (core/take-string (clipboard/get-clipboard-text)) ""))

(defsdl set-primary-selection-text! clipboard/set-primary-selection-text)
(defsdl has-primary-selection-text? clipboard/has-primary-selection-text :pred true)

(defn primary-selection-text
  "SDL_GetPrimarySelectionText (X11's middle-click selection); \"\" elsewhere."
  []
  (or (core/take-string (clipboard/get-primary-selection-text)) ""))

(defsdl clear-data! clipboard/clear-clipboard-data)
(defsdl has-data? clipboard/has-clipboard-data :pred true)

(defn data
  "SDL_GetClipboardData for a MIME type, as a byte-array, or nil when absent."
  [mime-type]
  (with-outs [size :size_t]
    (let [p (clipboard/get-clipboard-data mime-type size)]
      (when-not (ffi/null? p)
        (let [n (ffi/read size :size_t)
              bytes (ffi/read-array p n)]
          (core/free! p)
          bytes)))))

(defn mime-types
  "SDL_GetClipboardMimeTypes: the MIME types the clipboard currently offers."
  []
  (with-outs [n :size_t]
    (let [p (clipboard/get-clipboard-mime-types n)]
      (if (ffi/null? p)
        []
        (let [ss (core/read-strings p (ffi/read n :size_t))]
          (core/free! p)
          ss)))))
