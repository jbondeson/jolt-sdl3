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

(defn system-font
  "A TrueType font that ships with this OS, or nil when none of the usual ones is
  present."
  []
  (first (filter #(.exists (java.io.File. ^String %))
                 ["/System/Library/Fonts/Supplemental/Arial.ttf"      ; macOS
                  "/Library/Fonts/Arial.ttf"
                  "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"   ; Debian, Ubuntu
                  "/usr/share/fonts/dejavu/DejaVuSans.ttf"            ; Fedora
                  "/usr/share/fonts/TTF/DejaVuSans.ttf"               ; Arch
                  "C:/Windows/Fonts/arial.ttf"])))

(defn skip-or-fail
  "A test fixture cannot run because `why` (a library or file is missing). Locally
  that is a skip, with a note; where JOLT_SDL3_REQUIRE_SATELLITES is set (CI,
  which installs everything), it is a failure, so a broken install is not a quiet
  green run."
  [test-ns why]
  (if (System/getenv "JOLT_SDL3_REQUIRE_SATELLITES")
    (clojure.test/do-report {:type :fail :message (str test-ns ": " why)
                             :expected "the library installed" :actual why})
    (println (str test-ns ": " why "; skipping"))))
