(ns sdl3.filesystem
  "Where an application's files go: SDL_filesystem.h."
  (:require [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.filesystem :as fs]))

(defsdl base-path fs/get-base-path
  :doc "The directory the application was run from (with a trailing separator).")

(defn pref-path
  "SDL_GetPrefPath: a writable per-user directory for `org`/`app`, created if
  needed, with a trailing separator."
  [org app]
  (core/take-string (core/check-ptr "SDL_GetPrefPath" (fs/get-pref-path org app))))

(defn user-folder
  "SDL_GetUserFolder: :home :desktop :documents :downloads :music :pictures
  :publicshare :savedgames :screenshots :templates :videos."
  [folder]
  (fs/get-user-folder (core/enum c/folder folder)))

(defn current-directory
  "SDL_GetCurrentDirectory."
  []
  (core/take-string (core/check-ptr "SDL_GetCurrentDirectory" (fs/get-current-directory))))
