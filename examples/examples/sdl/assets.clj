(ns examples.sdl.assets
  "The files SDL's examples load (sample.png, sample.wav, ...) live in SDL's test/
  directory. They are not vendored here — sample.wav in particular is distributed
  with SDL by the artist's permission — so the first use of each downloads it from
  the pinned SDL release into examples/assets/ (gitignored), with curl through
  sdl3.process."
  (:require [clojure.java.io :as io]
            [sdl3.process :as process]))

(def sdl-release "release-3.4.16")

(def ^:private dir "examples/assets")

(defn path
  "The local path of SDL test asset `name`, downloading it first if needed."
  [name]
  (let [f (io/file dir name)]
    (when-not (.exists f)
      (.mkdirs (io/file dir))
      (let [url (str "https://raw.githubusercontent.com/libsdl-org/SDL/" sdl-release "/test/" name)
            {:keys [exit]} (process/sh "curl" "-sSfL" "-o" (str f) url)]
        (when-not (zero? exit)
          (.delete f)
          (throw (ex-info (str "could not download " url " (curl exited " exit ")") {:url url})))))
    (str f)))
