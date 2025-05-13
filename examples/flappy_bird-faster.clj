;; Flappy Bird clone with variable obstacle width and increasing speed

(import
  [android.content Context]
  [android.graphics Canvas Color Paint Paint$Align Rect RectF]
  [android.os Handler Looper SystemClock]
  [android.util Log]
  [android.view View ViewGroup$LayoutParams MotionEvent]
  [android.widget LinearLayout]
  [androidx.lifecycle LifecycleEventObserver Lifecycle$Event]
  [java.util Random])

(def TAG "ClojureApp")

;; Game constants
(def GAME-WIDTH 1080)
(def GAME-HEIGHT 1920)
(def BIRD-SIZE 120)
(def BASE-PIPE-WIDTH 200)  ;; Now a base width that will vary
(def MIN-PIPE-WIDTH 100)   ;; Minimum pipe width
(def MAX-PIPE-WIDTH 300)   ;; Maximum pipe width
(def PIPE-GAP 600)
(def GRAVITY 0.8)
(def JUMP-VELOCITY -20)
(def BASE-PIPE-SPEED 8)    ;; Base speed that will increase

;; Animation constants
(def FLAP-FRAMES 10) ;; How many frames the flap lasts

;; Game state vars
(def bird-y (atom (/ GAME-HEIGHT 2)))
(def bird-velocity (atom 0))
(def pipes (atom []))
(def score (atom 0))
(def game-over (atom false))
(def game-running (atom true))
(def speed-multiplier (atom 1.0))  ;; New: speed multiplier that increases with score

;; Bird flap state
(def bird-flap-frame (atom 0)) ;; 0 = not flapping, 1..FLAP-FRAMES = flapping
(def bird-flapping? (atom false))

(def debug-message (atom "Starting game..."))
(def frame-count (atom 0))

(defn log-d [msg]
  (Log/d TAG msg)
  (reset! debug-message msg))

(defn reset-game []
  (log-d "Game reset")
  (reset! bird-y (/ GAME-HEIGHT 2))
  (reset! bird-velocity 0)
  (reset! pipes [])
  (reset! score 0)
  (reset! game-over false)
  (reset! game-running true)
  (reset! frame-count 0)
  (reset! bird-flap-frame 0)
  (reset! bird-flapping? false)
  (reset! speed-multiplier 1.0))  ;; Reset speed multiplier

(defn create-pipe []
  (let [random (Random.)
        ;; Random pipe width between MIN and MAX
        pipe-width (+ MIN-PIPE-WIDTH (.nextInt random (- MAX-PIPE-WIDTH MIN-PIPE-WIDTH)))
        top-height (+ 200 (.nextInt random (- GAME-HEIGHT PIPE-GAP 400)))
        bottom-y (+ top-height PIPE-GAP)]
    {:x GAME-WIDTH
     :width pipe-width  ;; Store the width in the pipe data
     :top-height top-height
     :bottom-y bottom-y
     :passed false}))

(defn spawn-pipe []
  (let [new-pipe (create-pipe)]
    (log-d (str "Spawning pipe at x=" GAME-WIDTH " width=" (:width new-pipe)))
    (swap! pipes conj new-pipe)))

(defn update-pipes []
  (let [current-speed (int (* BASE-PIPE-SPEED @speed-multiplier))
        updated-pipes (mapv (fn [pipe]
                             (let [new-x (- (:x pipe) current-speed)
                                   passed? (and (not (:passed pipe)) (< new-x 250))]
                               (if passed?
                                 (do
                                   (swap! score inc)
                                   ;; Increase speed by 5% with each point
                                   (swap! speed-multiplier #(* % 1.05))
                                   (log-d (str "Score: " @score ", Speed multiplier: " @speed-multiplier))
                                   (assoc pipe :x new-x :passed true))
                                 (assoc pipe :x new-x))))
                           @pipes)
        filtered-pipes (filterv #(> (:x %) (- 0 (:width %))) updated-pipes)]
    (reset! pipes filtered-pipes)))

(defn check-collision []
  (let [current-bird-y @bird-y
        bird-x 250
        bird-rect (RectF. (float bird-x)
                         (float current-bird-y)
                         (float (+ bird-x BIRD-SIZE))
                         (float (+ current-bird-y BIRD-SIZE)))]
    (or (< current-bird-y 0)
        (> (+ current-bird-y BIRD-SIZE) GAME-HEIGHT)
        (some (fn [{:keys [x width top-height bottom-y]}]
                (or
                  ;; Collision with top pipe - using the pipe's actual width
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x width) bird-x)
                       (< current-bird-y top-height))
                  ;; Collision with bottom pipe - using the pipe's actual width
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x width) bird-x)
                       (> (+ current-bird-y BIRD-SIZE) bottom-y))))
              @pipes))))

(defn update-bird []
  (swap! bird-velocity + GRAVITY)
  (swap! bird-y + @bird-velocity))

(defn jump []
  (reset! bird-velocity JUMP-VELOCITY)
  (reset! bird-flap-frame 1)
  (reset! bird-flapping? true))

(defn update-flap []
  (when @bird-flapping?
    (if (>= @bird-flap-frame FLAP-FRAMES)
      (do
        (reset! bird-flap-frame 0)
        (reset! bird-flapping? false))
      (swap! bird-flap-frame inc))))

(defn update-game []
  (when @game-running
    (when (not @game-over)
      (swap! frame-count inc)
      (when (zero? (mod @frame-count 100)) (spawn-pipe))
      (update-bird)
      (update-flap)
      (update-pipes)
      (when (check-collision)
        (log-d "Collision detected! Game over")
        (reset! game-over true)))))

;; Drawing helper for bird with animated wings
(defn draw-bird [canvas bird-x bird-y ^Paint body-paint ^Paint wing-paint]
  (let [cx (+ bird-x (/ BIRD-SIZE 2))
        cy (+ bird-y (/ BIRD-SIZE 2))
        r (/ BIRD-SIZE 2)
        ;; Wing animation: angle in radians, varies from -30deg to +30deg
        wing-range (* Math/PI (/ 60.0 180.0)) ;; 60 degrees in radians
        phase (if @bird-flapping?
                ;; Animate using a sine wave over FLAP-FRAMES
                (let [progress (/ (dec @bird-flap-frame) FLAP-FRAMES)]
                  (* wing-range (Math/sin (* progress Math/PI))))
                0.0)
        ;; Wing centerpoints relative to body
        wing-offset-x (- r 10)
        wing-offset-y 20
        wing-length 65
        wing-width 30]
    ;; Draw body (circle)
    (.drawOval canvas
      (RectF. (float bird-x)
              (float bird-y)
              (float (+ bird-x BIRD-SIZE))
              (float (+ bird-y BIRD-SIZE)))
      body-paint)
    ;; Draw left wing
    (let [lx (- cx wing-offset-x)
          ly (+ cy wing-offset-y)
          angle (- (- (/ Math/PI 2) phase))
          wx (+ lx (* wing-length (Math/cos angle)))
          wy (+ ly (* wing-length (Math/sin angle)))
          ]
      (.drawRect canvas
        (RectF.
          (float (- lx (/ wing-width 2)))
          (float (- ly 10))
          (float (+ lx (/ wing-width 2)))
          (float (+ ly wing-length)))
        wing-paint)
      (.drawLine canvas
        (float lx) (float ly)
        (float wx) (float wy)
        wing-paint))
    ;; Draw right wing (mirror)
    (let [rx (+ cx wing-offset-x)
          ry (+ cy wing-offset-y)
          angle (+ (/ Math/PI 2) phase)
          wx (+ rx (* wing-length (Math/cos angle)))
          wy (+ ry (* wing-length (Math/sin angle)))]
      (.drawRect canvas
        (RectF.
          (float (- rx (/ wing-width 2)))
          (float (- ry 10))
          (float (+ rx (/ wing-width 2)))
          (float (+ ry wing-length)))
        wing-paint)
      (.drawLine canvas
        (float rx) (float ry)
        (float wx) (float wy)
        wing-paint))
    ;; Draw eye
    (let [eye-x (+ cx 25)
          eye-y (- cy 20)
          eye-radius 10]
      (.drawCircle canvas
        (float eye-x) (float eye-y) (float eye-radius)
        (doto (Paint.) (.setColor Color/BLACK))))
    ;; Draw beak
    (let [beak-x (+ cx 50)
          beak-y cy]
      (.drawOval canvas
        (RectF. (float beak-x)
                (float (- beak-y 7))
                (float (+ beak-x 20))
                (float (+ beak-y 7)))
        (doto (Paint.) (.setColor (Color/rgb 255 170 0)))))))

;; Game view with white background and animated bird
(defn create-game-view [context]
  (let [body-paint (doto (Paint.)
                     (.setColor Color/YELLOW)
                     (.setAntiAlias true))
        wing-paint (doto (Paint.)
                     (.setColor (Color/rgb 200 200 200)) ; light gray wings
                     (.setStrokeWidth 10)
                     (.setAntiAlias true))
        pipe-paint (doto (Paint.)
                     (.setColor (Color/rgb 0 150 0)))
        text-paint (doto (Paint.)
                     (.setColor Color/BLACK)
                     (.setTextSize 80)
                     (.setTextAlign Paint$Align/CENTER)
                     (.setAntiAlias true))
        debug-paint (doto (Paint.)
                      (.setColor Color/BLACK)
                      (.setTextSize 40)
                      (.setTextAlign Paint$Align/LEFT)
                      (.setAntiAlias true))
        game-view (proxy [View] [context]
                    (onDraw [canvas]
                      ;; Draw white background
                      (.drawColor canvas Color/WHITE)
                      ;; Draw pipes
                      (doseq [{:keys [x width top-height bottom-y]} @pipes]
                        ;; Top pipe - using the pipe's actual width
                        (.drawRect canvas
                          (float x)
                          0
                          (float (+ x width))
                          (float top-height)
                          pipe-paint)
                        ;; Bottom pipe - using the pipe's actual width
                        (.drawRect canvas
                          (float x)
                          (float bottom-y)
                          (float (+ x width))
                          (float GAME-HEIGHT)
                          pipe-paint))
                      ;; Draw animated bird
                      (draw-bird canvas 250 @bird-y body-paint wing-paint)
                      ;; Draw score in black
                      (.drawText canvas (str @score) (/ GAME-WIDTH 2) 150 text-paint)
                      ;; Draw debug info
                      (.drawText canvas @debug-message 20 50 debug-paint)
                      (.drawText canvas (str "Pipes: " (count @pipes) 
                                             ", Speed: " (format "%.2f" (* BASE-PIPE-SPEED @speed-multiplier)))
                                 20 100 debug-paint)
                      ;; Draw game over
                      (when @game-over
                        (.setTextSize text-paint 120)
                        (.drawText canvas "Game Over" (/ GAME-WIDTH 2) (/ GAME-HEIGHT 2) text-paint)
                        (.setTextSize text-paint 80)
                        (.drawText canvas "Tap to restart" (/ GAME-WIDTH 2) (+ (/ GAME-HEIGHT 2) 150) text-paint))))
        ;; Touch handler
        _ (doto game-view
            (.setOnTouchListener
              (reify android.view.View$OnTouchListener
                (onTouch [this view event]
                  (let [action (.getAction event)]
                    (when (= action MotionEvent/ACTION_DOWN)
                      (if @game-over
                        (reset-game)
                        (jump)))
                    true)))))]
    game-view))

(def game-loop-handler (atom nil))

(defn start-game-loop [game-view]
  (log-d "Starting game loop")
  (let [main-looper (Looper/getMainLooper)
        handler (Handler. main-looper)
        runnable-ref (atom nil)]
    (reset! runnable-ref
            (fn []
              (update-game)
              (.invalidate game-view)
              (.postDelayed handler @runnable-ref 16))) ;; 60fps
    (reset! game-loop-handler handler)
    (.post handler @runnable-ref)))

(defn stop-game-loop []
  (log-d "Stopping game loop")
  (when @game-loop-handler
    (.removeCallbacksAndMessages @game-loop-handler nil)))

(defn setup-lifecycle-observer []
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (log-d (str "Lifecycle event: " (.name event)))
      (case (.name event)
        "ON_RESUME" (reset! game-running true)
        "ON_PAUSE" (reset! game-running false)
        "ON_DESTROY" (stop-game-loop)
        nil))))

(defn -main []
  (log-d "Starting Flappy Bird game")
  (let [layout (doto (LinearLayout. *context*)
                 (.setLayoutParams (ViewGroup$LayoutParams.
                                     ViewGroup$LayoutParams/MATCH_PARENT
                                     ViewGroup$LayoutParams/MATCH_PARENT))
                 (.setOrientation LinearLayout/VERTICAL))
        game-view (create-game-view *context*)
        layout-params (android.widget.LinearLayout$LayoutParams.
                        android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                        android.widget.LinearLayout$LayoutParams/MATCH_PARENT)
        _ (.addView layout game-view layout-params)
        lifecycle-observer (setup-lifecycle-observer)]
    (try
      (.. *context* (getLifecycle) (addObserver lifecycle-observer))
      (catch Exception e
        (log-d (str "Error registering lifecycle observer: " (.getMessage e)))))
    (reset-game)
    (spawn-pipe)
    (start-game-loop game-view)
    (doto *content-layout*
      (.removeAllViews)
      (.addView layout))))
