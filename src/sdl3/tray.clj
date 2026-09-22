(ns sdl3.tray
  "System tray / menu bar icons with menus: SDL_tray.h. Needs :video.

      (def t (create-tray nil \"My app\"))
      (build-menu! (menu t)
        [{:label \"Show window\" :on-click (fn [_] (show!))}
         {:label \"Muted\" :checkbox true :checked false :on-click (fn [e] (toggle-mute! (checked? e)))}
         :separator
         {:label \"Recent\" :submenu [{:label \"a.txt\" :on-click open-a}]}
         {:label \"Quit\" :on-click (fn [_] (reset! running false))}])

  Clicks are delivered while the app pumps events (sdl3.events/poll!), on that
  thread; on-click fns get the entry. The callbacks live until destroy!."
  (:require [jolt.ffi :as ffi]
            [sdl3.core :as core :refer [defsdl]]
            [sdl3.consts :as c]
            [sdl3.raw.tray :as tray]))

;; tray -> the arena holding its entries' callbacks
(def ^:private arenas (atom {}))

(defn create-tray
  "SDL_CreateTray with an SDL_Surface icon (nil: the app icon) and tooltip."
  [icon tooltip]
  (let [t (core/check-ptr "SDL_CreateTray" (tray/create-tray (or icon ffi/null) tooltip))]
    (swap! arenas assoc t (ffi/shared-arena))
    t))

(defn destroy!
  "SDL_DestroyTray, releasing every entry's callback."
  [t]
  (tray/destroy-tray t)
  (when-let [a (get @arenas t)]
    (swap! arenas dissoc t)
    (ffi/close-arena a))
  nil)

(defn set-icon! [t icon] (tray/set-tray-icon t (or icon ffi/null)) nil)
(defsdl set-tooltip! tray/set-tray-tooltip)

(defn menu
  "The tray's menu, created on first use."
  [t]
  (or (core/nullable (tray/get-tray-menu t))
      (core/check-ptr "SDL_CreateTrayMenu" (tray/create-tray-menu t))))

(defn submenu
  "The submenu of an entry inserted with :submenu, created on first use."
  [entry]
  (or (core/nullable (tray/get-tray-submenu entry))
      (core/check-ptr "SDL_CreateTraySubmenu" (tray/create-tray-submenu entry))))

(defn entries
  "SDL_GetTrayEntries: the entries of `menu`, in order."
  [m]
  (core/with-outs [n :int]
    (let [p (tray/get-tray-entries m n)]
      (if (ffi/null? p)
        []
        (mapv (fn [i] (ffi/read p :pointer (* i (ffi/sizeof :pointer)))) (range (ffi/read n :int)))))))

(defsdl entry-parent tray/get-tray-entry-parent)
(defsdl menu-parent-entry tray/get-tray-menu-parent-entry :nullable true)
(defsdl menu-parent-tray tray/get-tray-menu-parent-tray :nullable true)

(defn tray-of
  "The tray an entry belongs to, however deep in submenus."
  [entry]
  (loop [m (tray/get-tray-entry-parent entry)]
    (or (core/nullable (tray/get-tray-menu-parent-tray m))
        (recur (tray/get-tray-entry-parent (tray/get-tray-menu-parent-entry m))))))

(defn insert-entry!
  "SDL_InsertTrayEntryAt: a new entry at `pos` (-1 appends). `label` nil makes a
  separator; `flags` are keywords from sdl3.consts/tray-entry-flags — :button,
  :checkbox or :submenu, plus :checked and :disabled."
  [m pos label flags]
  (core/check-ptr "SDL_InsertTrayEntryAt" (tray/insert-tray-entry-at m (int pos) label (core/flags c/tray-entry-flags flags))))

(defn remove-entry! [entry] (tray/remove-tray-entry entry) nil)
(defsdl set-label! tray/set-tray-entry-label)
(defsdl label tray/get-tray-entry-label)
(defsdl set-checked! tray/set-tray-entry-checked)
(defsdl checked? tray/get-tray-entry-checked :pred true)
(defsdl set-enabled! tray/set-tray-entry-enabled)
(defsdl enabled? tray/get-tray-entry-enabled :pred true)
(defsdl click! tray/click-tray-entry
  :doc "Act as though the user clicked the entry: toggles a checkbox and runs its callback.")
(defsdl update! tray/update-trays
  :doc "Process tray events when no event loop pumps them.")

(defn on-click!
  "Call (f entry) when the entry is clicked; nil removes the callback."
  [entry f]
  (if (nil? f)
    (tray/set-tray-entry-callback entry ffi/null ffi/null)
    (let [cb (ffi/callback (get @arenas (tray-of entry)) (fn [_ e] (f e) nil) [:pointer :pointer] :void)]
      (tray/set-tray-entry-callback entry cb ffi/null)))
  nil)

(defn build-menu!
  "Append entries to menu `m` from data: :separator, or a map with :label and any
  of :on-click (fn [entry]), :checkbox true, :checked, :disabled, and :submenu (a
  vector of the same). Answers the created entries."
  [m items]
  (mapv (fn [item]
          (if (= :separator item)
            (insert-entry! m -1 nil :button)
            (let [{:keys [label on-click checkbox checked disabled submenu]} item
                  e (insert-entry! m -1 label (cond-> [(cond submenu :submenu checkbox :checkbox :else :button)]
                                               checked (conj :checked)
                                               disabled (conj :disabled)))]
              (when on-click (on-click! e on-click))
              (when submenu (build-menu! (sdl3.tray/submenu e) submenu))
              e)))
        items))
