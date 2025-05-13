;; A simple Flappy Bird clone for Android using Clojure

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
(def PIPE-WIDTH 200)
(def PIPE-GAP 600)
(def GRAVITY 0.8)
(def JUMP-VELOCITY -20)
(def PIPE-SPEED 8)

;; Global variables for game state
(def bird-y (atom (/ GAME-HEIGHT 2)))
(def bird-velocity (atom 0))
(def pipes (atom []))
(def score (atom 0))
(def game-over (atom false))
(def game-running (atom true))

;; Debug tracking
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
  (reset! frame-count 0))

(defn create-pipe []
  (let [random (Random.)
        top-height (+ 200 (.nextInt random (- GAME-HEIGHT PIPE-GAP 400)))
        bottom-y (+ top-height PIPE-GAP)]
    {:x GAME-WIDTH
     :top-height top-height
     :bottom-y bottom-y
     :passed false}))

(defn spawn-pipe []
  (let [new-pipe (create-pipe)]
    (log-d (str "Spawning pipe at x=" GAME-WIDTH))
    (swap! pipes conj new-pipe)))

(defn update-pipes []
  (let [updated-pipes (mapv (fn [pipe]
                             (let [new-x (- (:x pipe) PIPE-SPEED)
                                   passed? (and (not (:passed pipe)) (< new-x 250))]
                               (if passed?
                                 (do
                                   (swap! score inc)
                                   (assoc pipe :x new-x :passed true))
                                 (assoc pipe :x new-x))))
                           @pipes)
        filtered-pipes (filterv #(> (:x %) (- 0 PIPE-WIDTH)) updated-pipes)]
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
        (some (fn [{:keys [x top-height bottom-y]}]
                (or 
                  ;; Collision with top pipe
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x PIPE-WIDTH) bird-x)
                       (< current-bird-y top-height))
                  ;; Collision with bottom pipe
                  (and (< x (+ bird-x BIRD-SIZE))
                       (> (+ x PIPE-WIDTH) bird-x)
                       (> (+ current-bird-y BIRD-SIZE) bottom-y))))
              @pipes))))

(defn update-bird []
  (swap! bird-velocity + GRAVITY)
  (swap! bird-y + @bird-velocity))

(defn jump []
  (reset! bird-velocity JUMP-VELOCITY))

(defn update-game []
  (when @game-running
    (when (not @game-over)
      ;; Increment frame counter
      (swap! frame-count inc)
      
      ;; Every 100 frames (about 1.6 seconds at 60fps), spawn a new pipe
      (when (zero? (mod @frame-count 100))
        (spawn-pipe))
      
      ;; Update bird position
      (update-bird)
      
      ;; Update pipe positions
      (update-pipes)
      
      ;; Check for collisions
      (when (check-collision)
        (log-d "Collision detected! Game over")
        (reset! game-over true)))))

;; Game view
(defn create-game-view [context]
  (let [bird-paint (doto (Paint.)
                     (.setColor Color/YELLOW))
        pipe-paint (doto (Paint.)
                     (.setColor (Color/rgb 0 150 0)))
        text-paint (doto (Paint.)
                     (.setColor Color/WHITE)
                     (.setTextSize 80)
                     (.setTextAlign Paint$Align/CENTER))
        debug-paint (doto (Paint.)
                      (.setColor Color/WHITE)
                      (.setTextSize 40)
                      (.setTextAlign Paint$Align/LEFT))
        game-view (proxy [View] [context]
                    (onDraw [canvas]
                      ;; Draw background
                      (.drawColor canvas Color/CYAN)
                      
                      ;; Draw pipes
                      (doseq [{:keys [x top-height bottom-y]} @pipes]
                        ;; Draw top pipe
                        (.drawRect canvas 
                                  (float x) 
                                  0 
                                  (float (+ x PIPE-WIDTH)) 
                                  (float top-height) 
                                  pipe-paint)
                        
                        ;; Draw bottom pipe
                        (.drawRect canvas 
                                  (float x) 
                                  (float bottom-y) 
                                  (float (+ x PIPE-WIDTH)) 
                                  (float GAME-HEIGHT) 
                                  pipe-paint))
                      
                      ;; Draw bird
                      (.drawOval canvas 
                                (RectF. (float 250) 
                                       (float @bird-y) 
                                       (float (+ 250 BIRD-SIZE)) 
                                       (float (+ @bird-y BIRD-SIZE))) 
                                bird-paint)
                      
                      ;; Draw score
                      (.drawText canvas (str @score) (/ GAME-WIDTH 2) 150 text-paint)
                      
                      ;; Draw debug info
                      (.drawText canvas @debug-message 20 50 debug-paint)
                      (.drawText canvas (str "Pipes: " (count @pipes) ", Frame: " @frame-count) 20 100 debug-paint)
                      
                      ;; Draw game over text
                      (when @game-over
                        (.setTextSize text-paint 120)
                        (.drawText canvas "Game Over" (/ GAME-WIDTH 2) (/ GAME-HEIGHT 2) text-paint)
                        (.setTextSize text-paint 80)
                        (.drawText canvas "Tap to restart" (/ GAME-WIDTH 2) (+ (/ GAME-HEIGHT 2) 150) text-paint))))
        
        ;; Handle touch events
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
              (.postDelayed handler @runnable-ref 16)))  ;; 60fps
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
    
    ;; Reset game and start the game loop
    (reset-game)
    
    ;; Force spawn a pipe immediately
    (spawn-pipe)
    
    (start-game-loop game-view)
    
    (doto *content-layout*
      (.removeAllViews)
      (.addView layout))))
