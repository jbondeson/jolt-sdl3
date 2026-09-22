(ns sdl3.hid
  "Raw HID devices: SDL_hidapi.h (SDL's copy of hidapi). For devices SDL has no
  driver for — custom controllers, LED boards, macro pads. Reports are
  byte-arrays whose first byte is the report id (0 when the device uses none).

      (hid/with-hid
        (let [dev (hid/open 0x1234 0x5678)]
          (hid/write! dev (byte-array [0 1 2 3]))
          (hid/read dev 64 1000)              ; up to 64 bytes within 1s, or nil
          (hid/close! dev)))

  HID strings are wchar_t in C — four bytes on macOS and Linux, two on
  Windows — and are decoded here to Clojure strings."
  (:refer-clojure :exclude [read])
  (:require [clojure.string :as str]
            [jolt.ffi :as ffi]
            [sdl3.core :as core]
            [sdl3.consts :as c]
            [sdl3.raw.hidapi :as hid]))

(def ^:private wchar-width
  (if (str/includes? (str/lower-case (or (System/getProperty "os.name") "")) "windows") 2 4))

(defn- read-wide
  "The NUL-terminated wchar_t string at `p` (nil for NULL)."
  [p]
  (when-not (ffi/null? p)
    (let [t (if (= 2 wchar-width) :uint16 :uint32)
          sb (StringBuilder.)]
      (loop [i 0]
        (let [cp (ffi/read p t (* i wchar-width))]
          (if (or (zero? cp) (> i 4096))
            (str sb)
            (do (.append sb (str (char cp))) (recur (inc i)))))))))

(defn- write-wide
  "`s` as a NUL-terminated wchar_t string in `arena`."
  [arena s]
  (let [cps (mapv int (seq s))
        t (if (= 2 wchar-width) :uint16 :uint32)
        p (ffi/alloc arena (* wchar-width (inc (count cps))))]
    (doseq [[i cp] (map-indexed vector cps)] (ffi/write p t cp (* i wchar-width)))
    p))

(defn- check [what n]
  (if (neg? n) (throw (core/sdl-error what)) n))

(defn init!
  "SDL_hid_init: start the HID layer (SDL_Init does this for joysticks; call it
  when using HID alone). Pair with exit!."
  []
  (check "SDL_hid_init" (hid/hid-init))
  nil)

(defn exit! [] (check "SDL_hid_exit" (hid/hid-exit)) nil)

(defmacro with-hid
  "Run body between init! and exit!."
  [& body]
  `(do (init!) (try ~@body (finally (exit!)))))

(defn device-change-count
  "Changes each time a device is added or removed: poll it to know when to enumerate again."
  []
  (hid/hid-device-change-count))

(defn- read-info [p]
  (-> (ffi/read p hid/hid-device-info)
      (dissoc :next)
      (update :path #(when-not (ffi/null? %) (ffi/ptr->string %)))
      (update :serial-number read-wide)
      (update :manufacturer-string read-wide)
      (update :product-string read-wide)
      (update :bus-type #(core/unenum c/hid-bus-type-names %))))

(defn enumerate
  "SDL_hid_enumerate: every HID device, or those matching `vendor-id` and
  `product-id` (0 matches any), as maps with :path :vendor-id :product-id
  :serial-number :release-number :manufacturer-string :product-string
  :usage-page :usage :interface-number :bus-type ..."
  ([] (enumerate 0 0))
  ([vendor-id product-id]
   (let [head (hid/hid-enumerate (int vendor-id) (int product-id))]
     (try
       (loop [p head acc []]
         (if (ffi/null? p)
           acc
           (recur (ffi/read p :pointer (ffi/field-offset hid/hid-device-info :next)) (conj acc (read-info p)))))
       (finally (when-not (ffi/null? head) (hid/hid-free-enumeration head)))))))

(defn open
  "SDL_hid_open: the first device with `vendor-id` and `product-id` (and
  `serial-number`, when given)."
  ([vendor-id product-id] (open vendor-id product-id nil))
  ([vendor-id product-id serial-number]
   (with-open [a (ffi/confined-arena)]
     (core/check-ptr "SDL_hid_open" (hid/hid-open (int vendor-id) (int product-id)
                                                  (if serial-number (write-wide a serial-number) ffi/null))))))

(defn open-path
  "SDL_hid_open_path: the device at a :path from enumerate."
  [path]
  (core/check-ptr "SDL_hid_open_path" (hid/hid-open-path path)))

(defn close! [dev] (check "SDL_hid_close" (hid/hid-close dev)) nil)
(defn properties [dev] (hid/hid-get-properties dev))

(defn device-info
  "SDL_hid_get_device_info of an opened device, as enumerate's maps."
  [dev]
  (read-info (core/check-ptr "SDL_hid_get_device_info" (hid/hid-get-device-info dev))))

(defn write!
  "SDL_hid_write an output report (first byte the report id); answers bytes written."
  [dev report]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/bytes->ptr a report)]
      (check "SDL_hid_write" (hid/hid-write dev p n)))))

(defn read
  "SDL_hid_read_timeout: an input report of up to `n` bytes, waiting up to `ms`
  (-1 blocks, 0 polls). Answers a byte-array, or nil when none arrived in time."
  ([dev n] (read dev n -1))
  ([dev n ms]
   (with-open [a (ffi/confined-arena)]
     (let [p (ffi/alloc a (max 1 n))
           got (check "SDL_hid_read_timeout" (hid/hid-read-timeout dev p n (int ms)))]
       (when (pos? got) (ffi/read-array p got))))))

(defn set-nonblocking!
  "Make read answer at once (nil when nothing is waiting) regardless of its timeout."
  [dev nonblocking?]
  (check "SDL_hid_set_nonblocking" (hid/hid-set-nonblocking dev (if nonblocking? 1 0)))
  nil)

(defn send-feature-report!
  "SDL_hid_send_feature_report (first byte the report id); answers bytes sent."
  [dev report]
  (with-open [a (ffi/confined-arena)]
    (let [[p n] (core/bytes->ptr a report)]
      (check "SDL_hid_send_feature_report" (hid/hid-send-feature-report dev p n)))))

(defn- get-report [what f dev report-id n]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (max 1 n))]
      (ffi/write p :uint8 report-id)
      (ffi/read-array p (check what (f dev p n))))))

(defn feature-report
  "SDL_hid_get_feature_report: feature report `report-id`, up to `n` bytes
  including the id byte, as a byte-array."
  [dev report-id n]
  (get-report "SDL_hid_get_feature_report" hid/hid-get-feature-report dev report-id n))

(defn input-report
  "SDL_hid_get_input_report, as feature-report."
  [dev report-id n]
  (get-report "SDL_hid_get_input_report" hid/hid-get-input-report dev report-id n))

(defn report-descriptor
  "SDL_hid_get_report_descriptor as a byte-array."
  [dev]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a 4096)]
      (ffi/read-array p (check "SDL_hid_get_report_descriptor" (hid/hid-get-report-descriptor dev p 4096))))))

(defn- wide-string [what f dev]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 256 wchar-width))]
      (check what (f dev p 256))
      (read-wide p))))

(defn manufacturer [dev] (wide-string "SDL_hid_get_manufacturer_string" hid/hid-get-manufacturer-string dev))
(defn product [dev] (wide-string "SDL_hid_get_product_string" hid/hid-get-product-string dev))
(defn serial-number [dev] (wide-string "SDL_hid_get_serial_number_string" hid/hid-get-serial-number-string dev))

(defn indexed-string [dev i]
  (with-open [a (ffi/confined-arena)]
    (let [p (ffi/alloc a (* 256 wchar-width))]
      (check "SDL_hid_get_indexed_string" (hid/hid-get-indexed-string dev (int i) p 256))
      (read-wide p))))

(defn ble-scan!
  "SDL_hid_ble_scan: start or stop scanning for Bluetooth LE devices (Apple platforms)."
  [active?]
  (hid/hid-ble-scan (boolean active?))
  nil)
