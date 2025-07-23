;; Snake game implementation with touch controls and collision detection

(import '[android.util Log]
        '[android.widget LinearLayout TextView Button]
        '[android.view View ViewGroup$LayoutParams MotionEvent View$OnClickListener]
        '[android.graphics Canvas Paint Color]
        '[android.content Context]
        '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event]
        '[java.util Timer TimerTask])

(def TAG "ClojureApp")

(defn debug-log [msg]
  (Log/d TAG (str msg)))

;; Game state
(def game-state (atom {:snake [[10 10]]
                      :direction :right
                      :food [15 15]
                      :score 0
                      :game-over false
                      :grid-size 20}))

(def game-timer (atom nil))

;; Custom view for drawing the game
(defn create-game-view []
  (proxy [View] [*context*]
    (onDraw [canvas]
      (let [state @game-state
            {:keys [snake food score game-over grid-size]} state
            width (.getWidth this)
            height (.getHeight this)
            cell-width (/ width grid-size)
            cell-height (/ height grid-size)
            paint (Paint.)]
        
        ;; Clear background
        (.setColor paint Color/BLACK)
        (.drawRect canvas 0 0 width height paint)
        
        ;; Draw snake
        (.setColor paint Color/GREEN)
        (doseq [[x y] snake]
          (.drawRect canvas
                    (* x cell-width)
                    (* y cell-height)
                    (* (inc x) cell-width)
                    (* (inc y) cell-height)
                    paint))
        
        ;; Draw food
        (.setColor paint Color/RED)
        (let [[fx fy] food]
          (.drawRect canvas
                    (* fx cell-width)
                    (* fy cell-height)
                    (* (inc fx) cell-width)
                    (* (inc fy) cell-height)
                    paint))
        
        ;; Draw game over text
        (when game-over
          (.setColor paint Color/WHITE)
          (.setTextSize paint 48)
          (.drawText canvas "GAME OVER" 50 (/ height 2) paint)
          (.drawText canvas (str "Score: " score) 50 (+ (/ height 2) 60) paint))))
    
    (onTouchEvent [event]
      (when (= (.getAction event) MotionEvent/ACTION_DOWN)
        (let [x (.getX event)
              y (.getY event)
              width (.getWidth this)
              height (.getHeight this)
              mid-x (/ width 2)
              mid-y (/ height 2)]
          
          ;; Determine direction based on touch position
          (cond
            (and (< (Math/abs (- x mid-x)) (Math/abs (- y mid-y)))
                 (< y mid-y)) (swap! game-state assoc :direction :up)
            (and (< (Math/abs (- x mid-x)) (Math/abs (- y mid-y)))
                 (> y mid-y)) (swap! game-state assoc :direction :down)
            (and (> (Math/abs (- x mid-x)) (Math/abs (- y mid-y)))
                 (< x mid-x)) (swap! game-state assoc :direction :left)
            (and (> (Math/abs (- x mid-x)) (Math/abs (- y mid-y)))
                 (> x mid-x)) (swap! game-state assoc :direction :right))
          
          (debug-log (str "Touch at " x "," y " - Direction: " (:direction @game-state)))
          true))
      true)))

(defn generate-food [snake grid-size]
  (let [all-positions (for [x (range grid-size)
                           y (range grid-size)]
                       [x y])
        available-positions (remove (set snake) all-positions)]
    (if (empty? available-positions)
      [0 0]
      (rand-nth available-positions))))

(defn move-snake [snake direction]
  (let [[head-x head-y] (first snake)
        new-head (case direction
                   :up [head-x (dec head-y)]
                   :down [head-x (inc head-y)]
                   :left [(dec head-x) head-y]
                   :right [(inc head-x) head-y])]
    (cons new-head snake)))

(defn check-collision [snake grid-size]
  (let [[head-x head-y] (first snake)]
    (or (< head-x 0)
        (>= head-x grid-size)
        (< head-y 0)
        (>= head-y grid-size)
        (some #{[head-x head-y]} (rest snake)))))

(defn update-game-state []
  (when-not (:game-over @game-state)
    (let [{:keys [snake direction food score grid-size]} @game-state
          new-snake (move-snake snake direction)
          head (first new-snake)
          ate-food (= head food)
          final-snake (if ate-food
                       new-snake
                       (butlast new-snake))
          collision (check-collision final-snake grid-size)]
      
      (if collision
        (do
          (debug-log "Game Over!")
          (swap! game-state assoc :game-over true))
        (swap! game-state assoc
               :snake final-snake
               :food (if ate-food
                      (generate-food final-snake grid-size)
                      food)
               :score (if ate-food (inc score) score))))))

(defn start-game-timer [game-view]
  (debug-log "Starting game timer")
  (let [timer (Timer.)
        task (proxy [TimerTask] []
               (run []
                 (update-game-state)
                 (.post game-view #(.invalidate game-view))))]
    (.scheduleAtFixedRate timer task 0 200)
    (reset! game-timer timer)))

(defn stop-game-timer []
  (debug-log "Stopping game timer")
  (when @game-timer
    (.cancel @game-timer)
    (reset! game-timer nil)))

(defn reset-game []
  (debug-log "Resetting game")
  (reset! game-state {:snake [[10 10]]
                      :direction :right
                      :food [15 15]
                      :score 0
                      :game-over false
                      :grid-size 20}))

(defn create-click-listener [game-view]
  (proxy [View$OnClickListener] []
    (onClick [v]
      (debug-log "Reset button clicked")
      (stop-game-timer)
      (reset-game)
      (start-game-timer game-view)
      (.invalidate game-view))))

(defn create-lifecycle-observer [game-view]
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (debug-log (str "Lifecycle event: " (.name event)))
      (case (.name event)
        "ON_START" (start-game-timer game-view)
        "ON_STOP" (stop-game-timer)
        "ON_DESTROY" (stop-game-timer)
        nil))))

(defn -main []
  (debug-log "Starting Snake Game")
  
  (try
    ;; Create main layout
    (let [main-layout (LinearLayout. *context*)
          game-view (create-game-view)
          score-text (TextView. *context*)
          reset-button (Button. *context*)
          click-listener (create-click-listener game-view)]
      
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setBackgroundColor main-layout Color/BLACK)
      
      ;; Setup score display
      (.setText score-text "Score: 0")
      (.setTextColor score-text Color/WHITE)
      (.setTextSize score-text 24)
      
      ;; Setup reset button
      (.setText reset-button "Reset Game")
      (.setOnClickListener reset-button click-listener)
      
      ;; Add views to layout
      (.addView main-layout score-text)
      (.addView main-layout game-view
        (android.widget.LinearLayout$LayoutParams.
          ViewGroup$LayoutParams/MATCH_PARENT
          0
          1.0))
      (.addView main-layout reset-button)
      
      ;; Set up lifecycle observer
      (let [lifecycle (.getLifecycle *context*)
            observer (create-lifecycle-observer game-view)]
        (debug-log "Registering lifecycle observer")
        (.addObserver lifecycle observer))
      
      ;; Add to content layout
      (.addView *content-layout* main-layout)
      
      ;; Update score display periodically
      (let [score-timer (Timer.)
            score-task (proxy [TimerTask] []
                        (run []
                          (.post score-text
                            #(.setText score-text (str "Score: " (:score @game-state))))))]
        (.scheduleAtFixedRate score-timer score-task 0 100))
      
      (debug-log "Snake game setup complete"))
    
    (catch Exception e
      (debug-log (str "Error in main: " (.getMessage e)))
          (.printStackTrace e))))
