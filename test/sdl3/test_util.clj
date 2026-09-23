(ns sdl3.test-util
  "Helpers shared by the tests.")

(defn temp-root
  "A temporary directory that exists on this machine. Jolt reports java.io.tmpdir
  as /tmp on Windows too, where no such directory exists, so the platform's own
  TEMP / TMPDIR comes first."
  []
  (let [dir (or (System/getenv "TEMP") (System/getenv "TMP") (System/getenv "TMPDIR")
                (System/getProperty "java.io.tmpdir"))]
    (.mkdirs (java.io.File. ^String dir))
    dir))

(defn temp-path
  "A fresh path under temp-root whose name starts with `prefix`."
  [prefix]
  (str (temp-root) "/" prefix "-" (System/nanoTime)))

(defn temp-dir
  "A fresh, existing directory under temp-root."
  [prefix]
  (let [p (temp-path prefix)]
    (.mkdirs (java.io.File. ^String p))
    p))
