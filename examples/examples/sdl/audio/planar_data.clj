(ns examples.sdl.audio.planar-data
  "Port of SDL's examples/audio/05-planar-data: draw two clickable buttons. Each
  causes a sound to play, fed to either the left or right audio channel through
  separate (\"planar\") arrays.

  The original C is public domain; so is this port."
  (:require [clojure.string :as str]
            [sdl3.core :as sdl]
            [sdl3.audio :as audio]
            [sdl3.render :as r]
            [sdl3.rect :as rect]
            [examples.sdl.app :as app]))

;; location of buttons on the screen.
(def ^:private rect-left-button {:x 100 :y 170 :w 100 :h 100})
(def ^:private rect-right-button {:x 440 :y 170 :w 100 :h 100})

;; SDL_DEBUG_TEXT_FONT_CHARACTER_SIZE: the debug font's glyphs are 8x8 pixels.
(def ^:private debug-char-size 8)

(declare left right)

(defn init [state _]
  (sdl/set-app-metadata! "Example Audio Planar Data" "1.0" "com.example.audio-planar-data")
  (app/try-init "Couldn't initialize SDL"
    (sdl/init! :video :audio)
    (let [[window renderer] (r/create-window-and-renderer "examples/audio/planar-data" 640 480 :resizable)]
      (r/set-logical-presentation! renderer 640 480 :letterbox)
      ;; Uint8 data, stereo, 4000Hz.
      (let [stream (audio/open-device-stream :playback {:format :u8 :channels 2 :freq 4000})]
        (audio/resume-stream-device! stream)  ; open-device-stream starts the device paused. Resume it!
        ;; :playing-sound is -1 if we're currently playing left, 1 if playing right,
        ;; 0 if not playing.
        (swap! state assoc :window window :renderer renderer :stream stream :playing-sound 0)
        :continue))))

(defn event [state e]
  (let [{:keys [renderer stream playing-sound]} @state]
    (cond
      (= :quit (:type e)) :success  ; end the program, reporting success to the OS.

      (= :mouse-button-down (:type e))
      (do
        (when (zero? playing-sound)  ; nothing currently playing?
          ;; SDL_ConvertEventToRenderCoordinates: window coordinates to the 640x480
          ;; logical space the buttons are placed in
          (let [point (r/coordinates-from-window renderer (:x e) (:y e))]
            (cond
              (rect/point-in-rect? point rect-left-button)  ; clicked left button?
              (do
                ;; nil says "this specific channel is silent"
                (audio/put-planar! stream [left nil] (alength ^bytes left))
                (audio/flush! stream)  ; that's all we're playing until it completes.
                (swap! state assoc :playing-sound -1))  ; left is playing

              (rect/point-in-rect? point rect-right-button)  ; clicked right button?
              (do
                (audio/put-planar! stream [nil right] (alength ^bytes right))
                (audio/flush! stream)
                (swap! state assoc :playing-sound 1)))))  ; right is playing
        :continue)

      :else :continue)))

(defn- render-button [renderer playing-sound rect label button-value]
  (if (= playing-sound button-value)
    (r/set-draw-color! renderer 0 255 0 255)   ; green while playing
    (r/set-draw-color! renderer 0 0 255 255))  ; blue while not playing
  (r/fill-rect! renderer rect)
  (r/set-draw-color! renderer 255 255 255 255)
  (let [x (+ (:x rect) (/ (- (:w rect) (* debug-char-size (count label))) 2.0))
        y (+ (:y rect) (/ (- (:h rect) debug-char-size) 2.0))]
    (r/debug-text! renderer x y label)))

(defn iterate [state]
  (let [{:keys [renderer stream playing-sound]} @state]
    (when (and (not (zero? playing-sound))
               (zero? (audio/queued stream)))  ; sound is done? We can play a new sound now.
      (swap! state assoc :playing-sound 0))
    (let [playing (:playing-sound @state)]
      (r/set-draw-color! renderer 0 0 0 255)
      (r/clear! renderer)
      (render-button renderer playing rect-left-button "LEFT" -1)
      (render-button renderer playing rect-right-button "RIGHT" 1)
      (r/present! renderer))
    :continue))

(defn quit [state _]
  (some-> (:stream @state) audio/destroy-stream!))

(defn -main [& args]
  (app/run {:init init :event event :iterate iterate :quit quit} args))

;; This is the audio data, as raw PCM samples (Uint8, 1 channel, 4000Hz), written
;; here as hex strings for convenience.

(defn- hex->bytes [^String s]
  (let [n (quot (count s) 2)
        out (byte-array n)]
    (dotimes [i n]
      (aset out i (unchecked-byte (Integer/parseInt (subs s (* 2 i) (+ 2 (* 2 i))) 16))))
    out))

(def ^:private left
  (hex->bytes
   (str
   "7f7f7f7f7f7f7f7f7f8080818081828283838383838282818080807f7e7e7e7d7b7b7b7b7c7d7d7e"
    "80818283848585848483817f7d7c7a7a7a77777776767677787d82898e929595918b847d77737272"
    "7475757576747373747981898f969b9c9891887e77747374777b7c7a77736d69686a737f878e99a1"
    "9e9790867c76777b80899193918e877c716b65605d5f60616b7b848da0aeaea8a19481736f70747e"
    "8d95979892837269615a56595d5f6575828795aab4b0aaa08d776c6c6d728191989a9a8f7a6a6158"
    "4f50575b6174858a96abb4aea59c887167696c7385969da1a3967f6e63564c4d5253586b808692aa"
    "b8b4aca59075696a6c7386989ca2a7997f6e61544c4b4d4f54667c8590a9bcbab4ac957869676771"
    "86999da4ab9b7f6e5f504b4e4e4e546077868ea4bbbfb9b39e7d6865636b849a9da3b09f83715f4d"
    "4c515151565a647d9099adc3c2b5aa927162656a7892a2a1a7a8917866554a50545050585a658b9b"
    "9bb7c9b3a6a27d5a666f7094a2909ba58f82775c5860504656493a5497bea9b0ad91a7b3836f6c5b"
    "71919cac98788aa6ad9e724d4e4f4e4a4846424e99d5aeb0b18ab3bd826b53568b97a7af746b92af"
    "c18f55474e605e454a4f3a449fdfaca89379bfc39267365a909bb6a16b688dc3ca834f3d53726346"
    "44554f4c78cbbb939979add09f70374f909eaf94737189c0c08f5b4562796f5b465654535990d895"
    "8c8c88d6b8834c2f80a2aa9c697480b0c699785469807c694b4e574e4c5faec3828683acd9a36a31"
    "50a0ada66d597f9ec8af8174708b8376585056595849627cce99719c8dd4b16c4f3795ab9b7f4b82"
    "a2bab57b7d7d8d8b7162545b4e5d4c5e579cd4679483a2d883702e59b59da1515597adcb86777895"
    "a1766d58675b4f6655674e67d98889866fcd9b894e399fa0a97a478899beac6b8887af9a67716374"
    "62555c5e655c54b1b0798d6facb78e73447ba199905a7097a0b489838e96a37e6f6c6a6b5b5a615e"
    "5d6366a0a67c8d83a4ad887b58759591927075939cab92848d919681706b6c6862595e695a5a685f"
    "a2b06d877ea0ba89785373a69b956c658e9aab977b858e9a91716b68656e585d705d6d675e807894"
    "987c9690a1a5827f707e9487878088928e968c898473726f716d5e616a70776f6d79767f77757e90"
    "a88c85989ba7937978799194878685868b89827c746d6c75756f6469747e837675858a8988788188"
    "83857e8088898c8d8a8b8888898581817e7c7c777d766f7d7f7873768384807f828680818381817e"
    "7d7b838b857a768387827d767b8083817a797d8281828283868080817e807d7a7e817e7e807f8182"
    "8081827f7f7d7c7f7b7b7d7a7a7e7e7c7c7f807f80828181807e807f817b7c7f7f817f7f80807f80"
    "7f7f837e7f8581838480848181838183808480808580817f8282818180818087817c807f807d7c7d"
    "808080827d81827e82818181808080827f807f7f817f807e81807e807e7f8080827f838380807f7f"
    "7f7e7e7f808080808081807f7f7f7e7f7e7d7e7d7c7d7c7c7d7c7d7e7f7e7e7f7d7f7f807f7e7f80"
    "7e807e7e807e807e7f7e7d7f7d7d7d7d7d7d7e7f7f7d7e7f7e807f7f807f8080807f807f7f7f7f81"
    "8080807f7f7f807f7f80807f80807f7f808181818181818081808283818281828282818183828282"
    "818382818180807e7e7e7f7f7e807d80817e7f7f807f7f8080808181808181807f7f7f807f7f7f7e"
    "7f81807f8181828180828280818180807e7d7f7e81817e7f827f7d7f7d817f7f807f807f807f7f80"
    "7f7e7f7f7e7c7d7e7d7d7e7d7e7c7e7e7c7e7d7e7e7e7d7d7c7b7c7b7b7b7b7b7b7c7c7d7c7d7d7d"
    "7d7d7c7d7d7d7d7d7d7d7d7d7e7d7e7e7e7e7e7e7e7f7f7f7f7f8080818181818181818181818181"
    "81818080808080808080808081818181818181818282828281818180808080818181808081818181"
    "818181828282828281828282828282828181808080808080808080808080808080808080807f7f7f"
    "7f7f7f7f7f7f8080808080808080808080807f7f7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7e7f7f"
    "7f7f7f7e7e7e7e7f7f7f7f7e7e7e7e7e7e7f7f807f807f7f7f7f7f7f7f7f807f8180838080808484"
    "7b7e80807e807e7f8181807f807f7e7e7f80807f81828080818180817f80808181818184837f7f80"
    "807f817e7e7f817f7f807f8080808080807f818282807f807f7f7e7e7e7e7f7e7f7f7f7d7e7e7f80"
    "80808081808080808081818080817f8080808080807f807f7e7e7f7f7e7e7e7f7e7d7d7e7e7e7e7e"
    "7e7e7f7e807e7f7f7e7f7e807f807f7f7f7f807f7f7e7f7f7f7f7f7f7f7f807f7f7f81807f808082"
    "81808080807f7f7f7f8181808180827f7f7e7e807e807f7f7f7f807f807f81808180808180838080"
    "7f7f807f807e807f7f807f8280817f7e807f807f7f7f7f807f7f7f807f7f807f8180808080808080"
    "808080807f7f808180808081817f7f7f7f7e7e7f7f8080807f80817f807e7f7f7e807e7f7f7f7f7e"
    "7e7d7f7e7f7f7f807f80807f807f8081818180807f7f807f7f7f81817f807f807f7f7f7f80807f7f"
    "7f80807f80808180807f7e7f7e7e7e7e7f7f7f7f7f807f80807f807e7f7e")))

(def ^:private right
  (hex->bytes
   (str
   "7f7e7e7f8080807f8081828382838383828181807f7e7c7b7a7a7979797a7a7b7c7e808284868889"
    "898988878482807e7c7b7a7a7978777576777878787b81878c8e9092918d87817d7b7a79797a7978"
    "7574757575767676767b83888b8f9598958d8683807e7c7c7e7e7c79787675727374726f6d727e87"
    "8b90989f9b91857f7b78797f878b8a8989868179757473736f6d6e6e6e6f7277828f95999c9e998c"
    "7f747170747e8a92918f8f8d857c76757675716d6b686464666e838f939ba3a49786766f6d6e7887"
    "9498969491897e746f7074726e6b6762606069849195a1aeb09b84746a6567788b989f9e9a90867c"
    "716a6c73746d69655e5c606f8b959bacb3a5897a6b5c5f708897a5aca1958e86766a6b72726c675e"
    "555256789c919cbcb898837f5e4c6c838a9ab7ae8a8f93796976766970705b5051575277b29095c8"
    "b18d898a554e877f82b3b98f8c9d7971806a617b705163623e50619aad7ebab5949f93754b7b796c"
    "abaf9f938e7a7f896a6e71665e635c5353505ab8bd6dc3b28aa7a1704c88637db1a1a68e6a7c958b"
    "84725c5c6764615665524480da8a88c98996b1924a6f6d78a5a7a098666ea69d957052577369725a"
    "5552503db8db5da9ab82adc3654c6c6d98ac9f97745aa0b19e7e525474716a6a5a534b465ee5aa62"
    "ab8f97cba54b4f6788a6a498846180b7b498644e6477727255544e523c96f0697fa280c1c875464d"
    "74a49e958a6a73a6b7b481605e717c746b5454484f44c3cb5b9b8699d4a3713e4b91999d95707285"
    "b0bda57e676778776e63535b395048b5c26aa577a8bd98893a608385a987877477aca9b98a716b6d"
    "816d6651603c504a91bf83ae7aa4a197924b7368868e8c95798386a2aba68d796a756874565c4e4c"
    "495db188b98da490948b667269837c91828979878aa19fa5958d7a6f6f6162585f52524f8090a1a6"
    "a39c9086746d6c7a838a8c887f80828f999ea39a938473685d5e5d5f5e5d526a7d8d9fa6aca0957d"
    "6e646a76818e98948e8484848691989d9a8c7b675c58585e5e646067757f8e99a2a5a29987746a67"
    "6e7b87969797948e8c8a8b8a8a88847b72665e58575a60646c78818c969d9fa1998f8076706c7076"
    "818a93979894918c8a87817c74716b686562606163676c77828a969ca4a5a095867b716e6e737982"
    "8b949998958e88817b77736f6c6a68676669696e7077818891979fa1a29b9282776c6a6b7179838d"
    "939796958f8b847d76716a6867686b6d717376797e838a8f949797958f877f797676787a7e81868a"
    "8c8e8d8a86807c7874717070727476787a7c7e80828281807e7f8184888b8d8d8b87837d7a77787a"
    "7e818384848482807f7d7b7a7878777878797c7e8183838483828180807f7f808081828384848483"
    "8381807f7f8080807f7e7d7c7c7c7d7d7f808081807f7e7d7d7d7e80808182828181818180808180"
    "81807f7f8080808180807e7d7d7e7e7f808080808080808080808180808081818181818180808080"
    "807f7f7e7e7f7f7f7f7f7f7e7e7e7e7f80818282828281807f7f7e7e7e7e7f7f7f7f7f7e7f7f7f7f"
    "80808181808080807f7f7e7f7e7f7f7f808081818181818181818181828282828181807f7e7e7e7e"
    "7e7f7f80808080808081808180808080807f7f7f7f7e7e7e7e7d7e7e7f80818282828181807f7f7f"
    "7f7f7f7f7f7f7f7f7f7f7f7f7f8080807f7f7f7f7f7f7f7f7f7f7f7f7f7f7f80808080808080807f"
    "8080807f7f7f7f7f7f7f7f807f8080807f807f807f7f7f80808181818181817f87837d81807e817b"
    "7d847f8183827f807c7b7d8080808080807e7f7e7f8080818282828280807f7f7e7e7e7f7f7f7f80"
    "82807f808180817f83857f8084837d7c7d807d7d7e7e7d83817d7d817f7c7c7c7d7c83808484827d"
    "7f7d7c7e7e7f818482817e7f7f7f7e8081807f807f7e7e7e7e7e8080808081817f7f7f7f81808281"
    "83818280807f7f807d807e817f817f807f7f7e7d8180827f817f7f7e7e7f7e807f817f8181818181"
    "82818180817f807f7f7e7e7f7e7f7f7e7f7e7f7e7e7e7e7f7e807f82808181808180818081808180"
    "807e7f7e7d7e7e7f7f7f7e7d807e7f7f807f7f817e81818380818080817f808080817f807f807d80"
    "7e7d8080837f837e837f807f7e817f7f8080817e7f7f808180837f827f827f80807e7f7d7e7d7e7e"
    "7f7e7f7f7f807f818280828082807f7e7f7e7f807e8080817f7f7e807d7e7e7f807f807e817e817f"
    "807f80817f807e817e807d8080808180827e837d807c7d7e7c7e7d7e7f7e7e807e817e817f818080"
    "80808081838081808080807f807e7f7f817f80807f7e8080818281828181818280807e8280848180"
    "7f81807f807d807d817f8180818180807e807f817f8181817f808080808080808180807e817f7f7e"
    "7e7f7f807f7f7e817e7f80807f7f7f7f817f807f80807f8080807f7f7f807f7e7d7e7e7f7f7f7f7f"
    "7f7e7f7f807f807f807f7f808080808180")))
