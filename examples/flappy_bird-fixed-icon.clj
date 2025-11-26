;; Flappy Bird clone with improved graphics, random spacing, and progressive speed

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
(def BASE-PIPE-WIDTH 200)
(def MIN-PIPE-WIDTH 150)
(def MAX-PIPE-WIDTH 250)
(def PIPE-GAP 550)
(def GRAVITY 0.9)
(def JUMP-VELOCITY -22)
(def BASE-PIPE-SPEED 9)

;; Spawning constants
(def MIN-SPAWN-GAP 500)  ;; Minimum horizontal distance between pipes
(def MAX-SPAWN-GAP 900)  ;; Maximum horizontal distance between pipes

;; Animation constants
(def FLAP-FRAMES 10)

;; Game state vars
(def bird-y (atom (/ GAME-HEIGHT 2)))
(def bird-velocity (atom 0))
(def pipes (atom []))
(def score (atom 0))
(def game-over (atom false))
(def game-running (atom true))
(def speed-multiplier (atom 1.0))
(def next-spawn-gap (atom 600)) ;; Distance required before next pipe

;; Bird flap state
(def bird-flap-frame (atom 0))
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
  (reset! speed-multiplier 1.0)
  (reset! next-spawn-gap 600))

(defn create-pipe []
  (let [random (Random.)
        pipe-width (+ MIN-PIPE-WIDTH (.nextInt random (- MAX-PIPE-WIDTH MIN-PIPE-WIDTH)))
        ;; Ensure pipes aren't too high or too low
        min-pipe-height 200
        available-height (- GAME-HEIGHT PIPE-GAP (* 2 min-pipe-height))
        top-height (+ min-pipe-height (.nextInt random available-height))
        bottom-y (+ top-height PIPE-GAP)]
    {:x GAME-WIDTH
     :width pipe-width
     :top-height top-height
     :bottom-y bottom-y
     :passed false}))

(defn spawn-pipe []
  (let [new-pipe (create-pipe)]
    ;; Set the gap for the *next* pipe randomly
    (reset! next-spawn-gap (+ MIN-SPAWN-GAP (rand-int (- MAX-SPAWN-GAP MIN-SPAWN-GAP))))
    (swap! pipes conj new-pipe)))

(defn update-pipes []
  (let [current-speed (int (* BASE-PIPE-SPEED @speed-multiplier))
        updated-pipes (mapv (fn [pipe]
                             (let [new-x (- (:x pipe) current-speed)
                                   passed? (and (not (:passed pipe)) (< new-x 250))]
                               (if passed?
                                 (do
                                   (swap! score inc)
                                   ;; Linear speed increase: +0.02 multiplier per pipe
                                   ;; This is smoother than exponential and keeps game playable longer
                                   (swap! speed-multiplier #(+ % 0.02))
                                   (log-d (str "Score: " @score ", Speed x" (format "%.2f" @speed-multiplier)))
                                   (assoc pipe :x new-x :passed true))
                                 (assoc pipe :x new-x))))
                           @pipes)
        filtered-pipes (filterv #(> (+ (:x %) (:width %)) 0) updated-pipes)]
    (reset! pipes filtered-pipes)))

(defn check-collision []
  (let [current-bird-y @bird-y
        bird-x 250
        ;; Shrink hit box slightly to be forgiving
        hit-margin 15
        bird-rect (RectF. (float (+ bird-x hit-margin))
                         (float (+ current-bird-y hit-margin))
                         (float (- (+ bird-x BIRD-SIZE) hit-margin))
                         (float (- (+ current-bird-y BIRD-SIZE) hit-margin)))]
    (or (< current-bird-y 0)
        (> (+ current-bird-y BIRD-SIZE) GAME-HEIGHT)
        (some (fn [{:keys [x width top-height bottom-y]}]
                (or
                  ;; Top pipe collision
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x width) bird-x)
                       (< (+ current-bird-y hit-margin) top-height))
                  ;; Bottom pipe collision
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x width) bird-x)
                       (> (- (+ current-bird-y BIRD-SIZE) hit-margin) bottom-y))))
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
      
      ;; Random Spawning Logic
      (let [last-pipe (last @pipes)]
        (if (nil? last-pipe)
          (spawn-pipe) ;; Start with a pipe
          (let [last-pipe-right-edge (+ (:x last-pipe) (:width last-pipe))
                distance-from-edge (- GAME-WIDTH last-pipe-right-edge)]
            ;; Spawn if the gap since the last pipe is large enough
            (when (> distance-from-edge @next-spawn-gap)
              (spawn-pipe)))))

      (update-bird)
      (update-flap)
      (update-pipes)
      (when (check-collision)
        (log-d "Collision detected! Game over")
        (reset! game-over true)))))

;; Improved Bird Drawing - Clean and Cute
(defn draw-bird [canvas bird-x bird-y ^Paint paint]
  ;; 1. Body (Yellow Circle)
  (.setColor paint (Color/rgb 255 215 0)) ;; Gold/Yellow
  (.drawOval canvas 
    (RectF. (float bird-x) (float bird-y) 
            (float (+ bird-x BIRD-SIZE)) (float (+ bird-y BIRD-SIZE))) 
    paint)
  
  ;; 2. Eye (White Circle with Black Pupil)
  (let [eye-radius (/ BIRD-SIZE 4.5)
        eye-x (+ bird-x (* BIRD-SIZE 0.7))
        eye-y (+ bird-y (* BIRD-SIZE 0.3))]
    ;; Eye White
    (.setColor paint Color/WHITE)
    (.drawCircle canvas (float eye-x) (float eye-y) (float eye-radius) paint)
    ;; Pupil
    (.setColor paint Color/BLACK)
    (.drawCircle canvas (float (+ eye-x 4)) (float eye-y) (float (/ eye-radius 2.5)) paint))

  ;; 3. Beak (Orange Oval)
  (.setColor paint (Color/rgb 255 140 0)) ;; Dark Orange
  (let [beak-x (+ bird-x (* BIRD-SIZE 0.8))
        beak-y (+ bird-y (* BIRD-SIZE 0.6))]
    (.drawOval canvas 
      (RectF. (float beak-x) (float (- beak-y 15)) 
              (float (+ beak-x 40)) (float (+ beak-y 15))) 
      paint))
      
  ;; 4. Simple Wing (White/Cream Oval)
  (.setColor paint (Color/rgb 255 255 220))
  (let [wing-x (+ bird-x (* BIRD-SIZE 0.2))
        wing-y (+ bird-y (* BIRD-SIZE 0.55))
        ;; Simple flap animation: move wing slightly up/down
        flap-offset (if @bird-flapping? -10 0)]
    (.drawOval canvas
      (RectF. (float wing-x) (float (+ wing-y flap-offset))
              (float (+ wing-x 50)) (float (+ (+ wing-y 35) flap-offset)))
      paint)))

(defn create-game-view [context]
  (let [paint (doto (Paint.) (.setAntiAlias true))
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
                      (.setColor paint (Color/rgb 0 180 0)) ;; Green pipes
                      (doseq [{:keys [x width top-height bottom-y]} @pipes]
                        ;; Top pipe
                        (.drawRect canvas
                          (float x) 0 (float (+ x width)) (float top-height) paint)
                        ;; Bottom pipe
                        (.drawRect canvas
                          (float x) (float bottom-y) (float (+ x width)) (float GAME-HEIGHT) paint)
                        ;; Pipe caps (darker green details)
                        (.setColor paint (Color/rgb 0 120 0))
                        (.drawRect canvas (float x) (float (- top-height 40)) (float (+ x width)) (float top-height) paint)
                        (.drawRect canvas (float x) (float bottom-y) (float (+ x width)) (float (+ bottom-y 40)) paint)
                        (.setColor paint (Color/rgb 0 180 0))) ;; Reset to normal green

                      ;; Draw improved bird
                      (draw-bird canvas 250 @bird-y paint)

                      ;; Draw score
                      (.drawText canvas (str @score) (/ GAME-WIDTH 2) 150 text-paint)
                      
                      ;; Draw debug info
                      (.drawText canvas @debug-message 20 50 debug-paint)
                      (.drawText canvas (str "Speed: " (format "%.2f" @speed-multiplier) "x") 20 100 debug-paint)

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
              (.postDelayed handler @runnable-ref 16))) ;; ~60fps
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
