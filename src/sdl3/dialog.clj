(ns sdl3.dialog
  "Native file dialogs: SDL_dialog.h. Needs :video.

  The dialogs are asynchronous: each call returns at once, and SDL answers later
  — on the main thread while events are pumped, or on a thread of its own,
  depending on the platform. The answer is a map:

    {:files [\"/path/a.png\" ...] :filter 0}   chosen (:filter is the index of
                                               the filter in use, or -1)
    {:files []}                                canceled
    {:error \"message\"}                         SDL could not show the dialog

  Give a callback, or omit it and take the promise the call answers:

      @(open-file {:filters [{:name \"Images\" :pattern \"png;jpg\"}]})

  Keep pumping events (sdl3.events/poll!) until the answer arrives — deref on
  the main thread without pumping would wait forever on platforms that answer
  there. Options: :window (make it modal to that window), :filters,
  :default-location, :allow-many (open-file and open-folder), plus :title,
  :accept and :cancel labels (show-with-properties only)."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core]
            [sdl3.consts :as c]
            [sdl3.properties :as props]
            [sdl3.raw.dialog :as dialog]))

;; each pending dialog's arena (callback + filter strings), dropped once answered
(def ^:private pending (atom #{}))

(defn- read-filelist [p]
  (loop [i 0 acc []]
    (let [s (ffi/read p :pointer (* i (ffi/sizeof :pointer)))]
      (if (ffi/null? s) acc (recur (inc i) (conj acc (ffi/ptr->string s)))))))

(defn- setup
  "An automatic arena holding the C callback and the filters, kept reachable
  until SDL answers. Answers [arena callback filters-ptr nfilters result]."
  [{:keys [filters]} f]
  (let [arena (ffi/auto-arena)
        result (promise)
        deliver! (fn [answer]
                   (swap! pending disj arena)
                   (deliver result answer)
                   (when f (f answer)))
        cb (ffi/callback arena
                         (fn [_ filelist filter-index]
                           (deliver! (if (ffi/null? filelist)
                                       {:error (core/error)}
                                       {:files (read-filelist filelist) :filter filter-index}))
                           nil)
                         [:pointer :pointer :int] :void :collect-safe)
        [fp n] (core/alloc-array arena dialog/dialog-file-filter
                                 (for [{:keys [name pattern]} filters]
                                   {:name (ffi/string->ptr arena (str name))
                                    :pattern (ffi/string->ptr arena (str pattern))}))]
    (swap! pending conj arena)
    [arena cb fp n result]))

(defn open-file
  "SDL_ShowOpenFileDialog. Answers a promise of the result; `f`, when given, is
  called with the result too."
  ([opts] (open-file opts nil))
  ([{:keys [window default-location allow-many] :as opts} f]
   (let [[_ cb fp n result] (setup opts f)]
     (dialog/show-open-file-dialog cb ffi/null (or window ffi/null) fp (int n) default-location (boolean allow-many))
     result)))

(defn save-file
  "SDL_ShowSaveFileDialog; see open-file."
  ([opts] (save-file opts nil))
  ([{:keys [window default-location] :as opts} f]
   (let [[_ cb fp n result] (setup opts f)]
     (dialog/show-save-file-dialog cb ffi/null (or window ffi/null) fp (int n) default-location)
     result)))

(defn open-folder
  "SDL_ShowOpenFolderDialog; see open-file (no :filters)."
  ([opts] (open-folder opts nil))
  ([{:keys [window default-location allow-many] :as opts} f]
   (let [[_ cb _ _ result] (setup (dissoc opts :filters) f)]
     (dialog/show-open-folder-dialog cb ffi/null (or window ffi/null) default-location (boolean allow-many))
     result)))

(defn show-with-properties
  "SDL_ShowFileDialogWithProperties: `type` :openfile, :savefile or :openfolder,
  with every option including :title, :accept and :cancel labels."
  ([type opts] (show-with-properties type opts nil))
  ([type {:keys [window default-location allow-many title accept cancel] :as opts} f]
   (let [[_ cb fp n result] (setup opts f)]
     (props/with-properties [p (cond-> {:file-dialog-many-boolean (boolean allow-many)}
                                 window (assoc :file-dialog-window-pointer {:pointer window})
                                 (pos? n) (assoc :file-dialog-filters-pointer {:pointer fp} :file-dialog-nfilters-number n)
                                 default-location (assoc :file-dialog-location-string default-location)
                                 title (assoc :file-dialog-title-string title)
                                 accept (assoc :file-dialog-accept-string accept)
                                 cancel (assoc :file-dialog-cancel-string cancel))]
       (dialog/show-file-dialog-with-properties (core/enum c/file-dialog-type type) cb ffi/null p))
     result)))
