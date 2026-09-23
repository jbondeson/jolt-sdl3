(ns examples.sdl.app
  "The harness the SDL example ports run on. SDL's examples are written against
  SDL's main-callbacks model — SDL_AppInit, SDL_AppEvent, SDL_AppIterate and
  SDL_AppQuit — and this runs the same four functions in a plain loop, so each
  port keeps the shape of its C original:

      (app/run {:init    (fn [state args] ... :continue)
                :event   (fn [state event] ... :continue)
                :iterate (fn [state] ... :continue)
                :quit    (fn [state result] ...)})

  `state` is an atom holding a map, standing in for the C examples' static
  variables. :init, :event and :iterate return :continue to keep going,
  :success to end the program normally, or :failure to end it with an error;
  :event and :quit are optional. Events arrive decoded by sdl3.events. SDL_Quit
  runs at the end, as SDL does after SDL_AppQuit."
  (:require [sdl3.core :as sdl]
            [sdl3.events :as ev]
            [sdl3.log :as log]))

(defn- continue? [r] (= :continue r))

(defn run
  "Run the app's callbacks until one returns :success or :failure. Answers the
  final result; exits the process with status 1 on :failure when `exit?`."
  ([callbacks] (run callbacks nil))
  ([{:keys [init event iterate quit]} args]
   (let [state (atom {})
         result (try
                  (let [r (init state args)]
                    (if-not (continue? r)
                      r
                      (loop []
                        (let [r (reduce (fn [_ e]
                                          (let [r (if event (event state e) :continue)]
                                            (if (continue? r) r (reduced r))))
                                        :continue
                                        (ev/poll-all!))
                              r (if (continue? r) (iterate state) r)]
                          (if (continue? r) (recur) r)))))
                  (catch Throwable t
                    (log/error! :application "example failed: " (or (ex-message t) t))
                    :failure))]
     (try (when quit (quit state result))
          (finally (sdl/quit!)))
     result)))

(defn failure
  "Log `message` and SDL_GetError the way the C examples do, and answer :failure."
  [message]
  (log/log! message ": " (sdl/error))
  :failure)

(defmacro try-init
  "Evaluate body; when it throws (an sdl3 call failed), log `message` with the
  error and answer :failure — the C examples' `if (!SDL_Foo(...)) { SDL_Log(...);
  return SDL_APP_FAILURE; }`."
  [message & body]
  `(try ~@body
        (catch clojure.lang.ExceptionInfo e#
          (log/log! ~message ": " (or (:sdl/error (ex-data e#)) (ex-message e#)))
          :failure)))
