(ns sdl3.rect
  "SDL_Rect / SDL_FRect / SDL_Point / SDL_FPoint as Clojure maps {:x :y :w :h} and
  {:x :y}, with the layouts and helpers to move them in and out of C memory.

  A rect on the jolt side is a map; a rect handed to C is a pointer to 16 bytes.
  The drawing calls in sdl3.render take maps and copy them into a scratch cell,
  so most programs never touch a pointer. Programs that draw many rects a frame
  build them once with `frects` and hand the array to fill-rects!."
  (:require [jolt.ffi :as ffi]
            [sdl3.raw.rect :as rect]))

;; layouts, re-exported
(def rect rect/rect)
(def frect rect/frect)
(def point rect/point)
(def fpoint rect/fpoint)

(defn- vec->rect [r]
  (if (map? r) r (zipmap [:x :y :w :h] r)))

(defn- vec->point [p]
  (if (map? p) p (zipmap [:x :y] p)))

(defn write-rect!
  "Write rect `r` (a map or [x y w h]) as an SDL_Rect at pointer `p`; answers p."
  [p r]
  (let [{:keys [x y w h]} (vec->rect r)]
    (ffi/write p rect/rect {:x (int x) :y (int y) :w (int w) :h (int h)})
    p))

(defn write-frect!
  "Write rect `r` (a map or [x y w h]) as an SDL_FRect at pointer `p`; answers p."
  [p r]
  (let [{:keys [x y w h]} (vec->rect r)]
    (ffi/write p rect/frect {:x (double x) :y (double y) :w (double w) :h (double h)})
    p))

(defn write-point!
  [p pt]
  (let [{:keys [x y]} (vec->point pt)]
    (ffi/write p rect/point {:x (int x) :y (int y)})
    p))

(defn write-fpoint!
  [p pt]
  (let [{:keys [x y]} (vec->point pt)]
    (ffi/write p rect/fpoint {:x (double x) :y (double y)})
    p))

(defn read-rect [p] (ffi/read p rect/rect))
(defn read-frect [p] (ffi/read p rect/frect))
(defn read-point [p] (ffi/read p rect/point))
(defn read-fpoint [p] (ffi/read p rect/fpoint))

(defn alloc-rect
  "An SDL_Rect in `arena` (or caller-owned without one), optionally initialized."
  ([] (ffi/alloc rect/rect))
  ([arena] (ffi/alloc arena rect/rect))
  ([arena r] (write-rect! (ffi/alloc arena rect/rect) r)))

(defn alloc-frect
  "An SDL_FRect in `arena` (or caller-owned without one), optionally initialized."
  ([] (ffi/alloc rect/frect))
  ([arena] (ffi/alloc arena rect/frect))
  ([arena r] (write-frect! (ffi/alloc arena rect/frect) r)))

(defn- array-of [arena layout write! items]
  (let [n (count items)
        size (ffi/layout-size layout)
        p (ffi/alloc arena (* (max n 1) size))]
    (doseq [[i item] (map-indexed vector items)]
      (write! (ffi/slice p (* i size)) item))
    [p n]))

(defn frects
  "A contiguous SDL_FRect array in `arena` from a seq of rects; answers [pointer count]
  for fill-rects!, draw-rects! and friends."
  [arena rs]
  (array-of arena rect/frect write-frect! rs))

(defn rects
  "A contiguous SDL_Rect array in `arena`; answers [pointer count]."
  [arena rs]
  (array-of arena rect/rect write-rect! rs))

(defn fpoints
  "A contiguous SDL_FPoint array in `arena`; answers [pointer count]."
  [arena pts]
  (array-of arena rect/fpoint write-fpoint! pts))

(defn points
  "A contiguous SDL_Point array in `arena`; answers [pointer count]."
  [arena pts]
  (array-of arena rect/point write-point! pts))

;; ---------------------------------------------------------------------------
;; the helpers SDL defines inline (so there is no symbol to bind)
;; ---------------------------------------------------------------------------

(defn rect-empty?
  "SDL_RectEmpty: nil, or a rect with no positive area."
  [r]
  (or (nil? r)
      (let [{:keys [w h]} (vec->rect r)] (or (<= w 0) (<= h 0)))))

(defn rects-equal?
  "SDL_RectsEqual on the :x :y :w :h of both."
  [a b]
  (and a b (= (select-keys (vec->rect a) [:x :y :w :h]) (select-keys (vec->rect b) [:x :y :w :h]))))

(defn point-in-rect?
  "SDL_PointInRect: is point `p` inside rect `r`?"
  [p r]
  (let [{px :x py :y} (vec->point p)
        {:keys [x y w h]} (vec->rect r)]
    (and (>= px x) (< px (+ x w)) (>= py y) (< py (+ y h)))))

(defn rect->frect
  "SDL_RectToFRect."
  [r]
  (let [{:keys [x y w h]} (vec->rect r)]
    {:x (double x) :y (double y) :w (double w) :h (double h)}))

;; ---------------------------------------------------------------------------
;; the ones SDL exports
;; ---------------------------------------------------------------------------

(defn- with-two [layout write! a b f]
  (with-open [arena (ffi/confined-arena)]
    (f (write! (ffi/alloc arena layout) a) (write! (ffi/alloc arena layout) b) arena)))

(defn has-intersection?
  "SDL_HasRectIntersection."
  [a b]
  (with-two rect/rect write-rect! a b (fn [pa pb _] (rect/has-rect-intersection pa pb))))

(defn has-intersection-float?
  "SDL_HasRectIntersectionFloat."
  [a b]
  (with-two rect/frect write-frect! a b (fn [pa pb _] (rect/has-rect-intersection-float pa pb))))

(defn intersection
  "SDL_GetRectIntersection: the intersection of two rects as a map, or nil when
  they do not intersect."
  [a b]
  (with-two rect/rect write-rect! a b
    (fn [pa pb arena]
      (let [out (ffi/alloc arena rect/rect)]
        (when (rect/get-rect-intersection pa pb out) (read-rect out))))))

(defn intersection-float
  "SDL_GetRectIntersectionFloat."
  [a b]
  (with-two rect/frect write-frect! a b
    (fn [pa pb arena]
      (let [out (ffi/alloc arena rect/frect)]
        (when (rect/get-rect-intersection-float pa pb out) (read-frect out))))))

(defn union
  "SDL_GetRectUnion: the smallest rect containing both."
  [a b]
  (with-two rect/rect write-rect! a b
    (fn [pa pb arena]
      (let [out (ffi/alloc arena rect/rect)]
        (when (rect/get-rect-union pa pb out) (read-rect out))))))

(defn union-float
  "SDL_GetRectUnionFloat."
  [a b]
  (with-two rect/frect write-frect! a b
    (fn [pa pb arena]
      (let [out (ffi/alloc arena rect/frect)]
        (when (rect/get-rect-union-float pa pb out) (read-frect out))))))

(defn enclosing-points
  "SDL_GetRectEnclosingPoints: the smallest rect around `pts` (optionally clipped
  to `clip`), or nil when no point is inside the clip."
  ([pts] (enclosing-points pts nil))
  ([pts clip]
   (with-open [arena (ffi/confined-arena)]
     (let [[p n] (points arena pts)
           pc (when clip (alloc-rect arena clip))
           out (ffi/alloc arena rect/rect)]
       (when (rect/get-rect-enclosing-points p n (or pc ffi/null) out) (read-rect out))))))
