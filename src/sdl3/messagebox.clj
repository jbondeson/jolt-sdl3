(ns sdl3.messagebox
  "Modal message boxes: SDL_messagebox.h. Flags are keywords from
  sdl3.consts/messagebox-flags: :error :warning :information, plus
  :buttons-left-to-right / :buttons-right-to-left on the box and
  :button-returnkey-default / :button-escapekey-default on a button."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [with-outs]]
            [sdl3.consts :as c]
            [sdl3.raw.messagebox :as mb]))

(defn show-simple!
  "SDL_ShowSimpleMessageBox: a box with one OK button; blocks until dismissed.
  Works before init! and after a failed one."
  ([flags title message] (show-simple! flags title message nil))
  ([flags title message window]
   (core/check-bool "SDL_ShowSimpleMessageBox"
                    (mb/show-simple-message-box (core/flags c/messagebox-flags flags) (str title) (str message) (or window ffi/null)))
   nil))

(defn show!
  "SDL_ShowMessageBox with buttons:

      (show! {:flags :warning :title \"Unsaved changes\" :message \"Save before quitting?\"
              :buttons [{:id 1 :text \"Save\" :flags :button-returnkey-default}
                        {:id 2 :text \"Discard\"}
                        {:id 0 :text \"Cancel\" :flags :button-escapekey-default}]})

  Answers the :id of the button pressed, or nil when the box was closed some
  other way. :window is optional."
  [{:keys [flags title message window buttons]}]
  (with-open [a (ffi/confined-arena)]
    (let [n (count buttons)
          bsize (ffi/layout-size mb/message-box-button-data)
          barr (ffi/alloc a (* (max 1 n) bsize))]
      (doseq [[i b] (map-indexed vector buttons)]
        (ffi/write (ffi/slice barr (* i bsize)) mb/message-box-button-data
                   {:flags (core/flags c/messagebox-flags (:flags b))
                    :button-id (int (:id b i))
                    :text (ffi/string->ptr a (str (:text b)))}))
      (let [data (ffi/alloc a mb/message-box-data)]
        (ffi/write data mb/message-box-data
                   {:flags (core/flags c/messagebox-flags flags)
                    :window (or window ffi/null)
                    :title (ffi/string->ptr a (str title))
                    :message (ffi/string->ptr a (str message))
                    :numbuttons n
                    :buttons barr
                    :color-scheme ffi/null})
        (with-outs [id :int]
          (ffi/write id :int -1)
          (core/check-bool "SDL_ShowMessageBox" (mb/show-message-box data id))
          (let [v (ffi/read id :int)]
            (when-not (neg? v) v)))))))
