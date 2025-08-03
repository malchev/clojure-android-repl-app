;; Tetris game implementation with touch controls and custom rendering

(import '(android.content Context)
        '(android.graphics Canvas Color Paint Paint$Style)
        '(android.view View MotionEvent)
        '(android.widget LinearLayout TextView)
        '(android.os Handler)
        '(androidx.lifecycle LifecycleEventObserver Lifecycle$Event)
        '(android.util Log))

(def TAG "ClojureApp")

;; Game constants
(def BOARD-WIDTH 10)
(def BOARD-HEIGHT 20)
(def CELL-SIZE 40)

;; Tetris pieces (tetrominoes)
(def PIECES
  {:I [[[1 1 1 1]]]
   :O [[[1 1]
        [1 1]]]
   :T [[[0 1 0]
        [1 1 1]]
       [[1 0]
        [1 1]
        [1 0]]
       [[1 1 1]
        [0 1 0]]
       [[0 1]
        [1 1]
        [0 1]]]
   :S [[[0 1 1]
        [1 1 0]]
       [[1 0]
        [1 1]
        [0 1]]]
   :Z [[[1 1 0]
        [0 1 1]]
       [[0 1]
        [1 1]
        [1 0]]]
   :J [[[1 0 0]
        [1 1 1]]
       [[1 1]
        [1 0]
        [1 0]]
       [[1 1 1]
        [0 0 1]]
       [[0 1]
        [0 1]
        [1 1]]]
   :L [[[0 0 1]
        [1 1 1]]
       [[1 0]
        [1 0]
        [1 1]]
       [[1 1 1]
        [1 0 0]]
       [[1 1]
        [0 1]
        [0 1]]]})

(def PIECE-COLORS
  {:I Color/CYAN
   :O Color/YELLOW
   :T Color/MAGENTA
   :S Color/GREEN
   :Z Color/RED
   :J Color/BLUE
   :L (Color/rgb 255 165 0)})

;; Game state
(def game-state
  (atom {:board (vec (repeat BOARD-HEIGHT (vec (repeat BOARD-WIDTH 0))))
         :current-piece nil
         :current-type nil
         :current-x 0
         :current-y 0
         :current-rotation 0
         :score 0
         :game-over false
         :paused false}))

(defn debug-log [msg]
  (Log/d TAG (str "DEBUG: " msg)))

(defn create-empty-board []
  (vec (repeat BOARD-HEIGHT (vec (repeat BOARD-WIDTH 0)))))

(defn get-piece-shape [piece-type rotation]
  (let [rotations (get PIECES piece-type)]
    (nth rotations (mod rotation (count rotations)))))

(defn random-piece-type []
  (rand-nth (keys PIECES)))

(defn spawn-piece []
  (let [piece-type (random-piece-type)
        piece-shape (get-piece-shape piece-type 0)]
    {:type piece-type
     :shape piece-shape
     :x (int (/ (- BOARD-WIDTH (count (first piece-shape))) 2))
     :y 0
     :rotation 0}))

(defn valid-position? [board piece x y]
  (let [shape (:shape piece)]
    (every? true?
            (for [row (range (count shape))
                  col (range (count (first shape)))
                  :when (= 1 (get-in shape [row col]))]
              (let [board-x (+ x col)
                    board-y (+ y row)]
                (and (>= board-x 0)
                     (< board-x BOARD-WIDTH)
                     (>= board-y 0)
                     (< board-y BOARD-HEIGHT)
                     (= 0 (get-in board [board-y board-x]))))))))

(defn place-piece [board piece x y]
  (let [shape (:shape piece)
        piece-type (:type piece)]
    (reduce
     (fn [new-board [row col]]
       (if (= 1 (get-in shape [row col]))
         (assoc-in new-board [(+ y row) (+ x col)] piece-type)
         new-board))
     board
     (for [row (range (count shape))
           col (range (count (first shape)))]
       [row col]))))

(defn clear-lines [board]
  (let [full-lines (filter #(every? (complement zero?) (nth board %))
                          (range BOARD-HEIGHT))
        cleared-board (reduce (fn [b line-idx]
                               (vec (concat [(vec (repeat BOARD-WIDTH 0))]
                                          (subvec b 0 line-idx)
                                          (subvec b (inc line-idx)))))
                             board
                             (reverse full-lines))]
    {:board cleared-board
     :lines-cleared (count full-lines)}))

(defn move-piece [direction]
  (swap! game-state
         (fn [state]
           (if (or (:game-over state) (:paused state))
             state
             (let [current-piece {:type (:current-type state)
                                 :shape (get-piece-shape (:current-type state) (:current-rotation state))}
                   new-x (case direction
                          :left (dec (:current-x state))
                          :right (inc (:current-x state))
                          (:current-x state))
                   new-y (case direction
                          :down (inc (:current-y state))
                          (:current-y state))]
               (if (valid-position? (:board state) current-piece new-x new-y)
                 (assoc state :current-x new-x :current-y new-y)
                 state))))))

(defn rotate-piece []
  (swap! game-state
         (fn [state]
           (if (or (:game-over state) (:paused state))
             state
             (let [new-rotation (inc (:current-rotation state))
                   new-shape (get-piece-shape (:current-type state) new-rotation)
                   new-piece {:type (:current-type state) :shape new-shape}]
               (if (valid-position? (:board state) new-piece (:current-x state) (:current-y state))
                 (assoc state :current-rotation new-rotation)
                 state))))))

(defn drop-piece []
  (swap! game-state
         (fn [state]
           (if (or (:game-over state) (:paused state))
             state
             (let [current-piece {:type (:current-type state)
                                 :shape (get-piece-shape (:current-type state) (:current-rotation state))}
                   new-y (inc (:current-y state))]
               (if (valid-position? (:board state) current-piece (:current-x state) new-y)
                 (assoc state :current-y new-y)
                 (let [new-board (place-piece (:board state) current-piece (:current-x state) (:current-y state))
                       {cleared-board :board lines-cleared :lines-cleared} (clear-lines new-board)
                       new-piece (spawn-piece)
                       new-score (+ (:score state) (* lines-cleared 100))]
                   (if (valid-position? cleared-board new-piece (:x new-piece) (:y new-piece))
                     (assoc state
                            :board cleared-board
                            :current-type (:type new-piece)
                            :current-x (:x new-piece)
                            :current-y (:y new-piece)
                            :current-rotation (:rotation new-piece)
                            :score new-score)
                     (assoc state :game-over true)))))))))

(defn init-game []
  (debug-log "Initializing game")
  (let [first-piece (spawn-piece)]
    (reset! game-state
            {:board (create-empty-board)
             :current-type (:type first-piece)
             :current-x (:x first-piece)
             :current-y (:y first-piece)
             :current-rotation (:rotation first-piece)
             :score 0
             :game-over false
             :paused false})))

(defn draw-cell [canvas paint x y color draw-border]
  (.setColor paint color)
  (.setStyle paint Paint$Style/FILL)
  (.drawRect canvas x y (+ x CELL-SIZE) (+ y CELL-SIZE) paint)
  (when draw-border
    (.setColor paint Color/WHITE)
    (.setStyle paint Paint$Style/STROKE)
    (.setStrokeWidth paint 2)
    (.drawRect canvas x y (+ x CELL-SIZE) (+ y CELL-SIZE) paint)))

(defn create-tetris-view []
  (proxy [View] [*context*]
    (onDraw [canvas]
      (let [state @game-state
            paint (Paint.)
            board (:board state)]
        
        (.setAntiAlias paint true)
        
        ;; Clear background
        (.setColor paint Color/BLACK)
        (.setStyle paint Paint$Style/FILL)
        (.drawRect canvas 0 0 (.getWidth this) (.getHeight this) paint)
        
        ;; Draw board cells
        (doseq [row (range BOARD-HEIGHT)
                col (range BOARD-WIDTH)]
          (let [cell-value (get-in board [row col])
                x (* col CELL-SIZE)
                y (* row CELL-SIZE)]
            (if (not= 0 cell-value)
              (draw-cell canvas paint x y (get PIECE-COLORS cell-value Color/WHITE) true)
              (do
                (.setColor paint (Color/rgb 32 32 32))
                (.setStyle paint Paint$Style/STROKE)
                (.setStrokeWidth paint 1)
                (.drawRect canvas x y (+ x CELL-SIZE) (+ y CELL-SIZE) paint)))))
        
        ;; Draw current piece
        (when (and (:current-type state) (not (:game-over state)))
          (let [piece-shape (get-piece-shape (:current-type state) (:current-rotation state))
                piece-x (:current-x state)
                piece-y (:current-y state)
                piece-color (get PIECE-COLORS (:current-type state) Color/WHITE)]
            (doseq [row (range (count piece-shape))
                    col (range (count (first piece-shape)))
                    :when (= 1 (get-in piece-shape [row col]))]
              (let [x (* (+ piece-x col) CELL-SIZE)
                    y (* (+ piece-y row) CELL-SIZE)]
                (draw-cell canvas paint x y piece-color true)))))
        
        ;; Draw game over text
        (when (:game-over state)
          (.setColor paint Color/RED)
          (.setStyle paint Paint$Style/FILL)
          (.setTextSize paint 48)
          (.setTextAlign paint Paint$Align/CENTER)
          (.drawText canvas "GAME OVER" (/ (.getWidth this) 2) (/ (.getHeight this) 2) paint)
          (.setTextSize paint 24)
          (.drawText canvas "Tap to restart" (/ (.getWidth this) 2) (+ (/ (.getHeight this) 2) 60) paint))))
    
    (onTouchEvent [event]
      (let [action (.getAction event)
            x (.getX event)
            y (.getY event)]
        (when (= action MotionEvent/ACTION_DOWN)
          (if (:game-over @game-state)
            (init-game)
            (cond
              (< x (/ (.getWidth this) 3)) (move-piece :left)
              (> x (* 2 (/ (.getWidth this) 3))) (move-piece :right)
              :else (rotate-piece))))
        true))))

(defn create-game-loop []
  (let [handler (Handler.)]
    (letfn [(game-tick []
              (when (not (:paused @game-state))
                (drop-piece))
              (.postDelayed handler game-tick 1000))]
      (.postDelayed handler game-tick 1000))))

(defn -main []
  (debug-log "Starting Tetris game")
  
  (try
    (init-game)
    
    (let [main-layout (LinearLayout. *context*)
          game-view (create-tetris-view)
          score-text (TextView. *context*)]
      
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setBackgroundColor main-layout Color/BLACK)
      (.setPadding main-layout 20 20 20 20)
      
      (.setText score-text "Score: 0")
      (.setTextColor score-text Color/WHITE)
      (.setTextSize score-text 24)
      (.setPadding score-text 0 0 0 20)
      
      (.addView main-layout score-text)
      (.addView main-layout game-view 
                (android.widget.LinearLayout$LayoutParams
                 (* BOARD-WIDTH CELL-SIZE)
                 (* BOARD-HEIGHT CELL-SIZE)))
      
      (.addView *content-layout* main-layout)
      
      (let [score-handler (Handler.)]
        (letfn [(update-score []
                  (.setText score-text (str "Score: " (:score @game-state)
                                           (when (:game-over @game-state) " - GAME OVER")))
                  (.invalidate game-view)
                  (.postDelayed score-handler update-score 100))]
          (.postDelayed score-handler update-score 100)))
      
      (create-game-loop)
      
      (let [lifecycle (.. *context* (getLifecycle))
            observer (proxy [LifecycleEventObserver] []
                       (onStateChanged [source event]
                         (debug-log (str "Lifecycle event: " (.name event)))
                         (case (.name event)
                           "ON_PAUSE" (swap! game-state assoc :paused true)
                           "ON_RESUME" (swap! game-state assoc :paused false)
                           nil)))]
        (.addObserver lifecycle observer)))
    
    (debug-log "Tetris game initialized successfully")
    
    (catch Exception e
      (Log/e TAG "Error in Tetris game" e))))
