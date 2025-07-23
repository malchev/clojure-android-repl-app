;; A simple Snake game for Android using Clojure.
(def log-tag "ClojureApp")

(import android.util.Log
        android.view.View
        android.view.View$OnClickListener
        android.view.ViewGroup
        android.widget.LinearLayout
        android.widget.LinearLayout$LayoutParams
        android.widget.TextView
        android.widget.Button
        android.widget.GridLayout
        android.widget.GridLayout$LayoutParams
        android.graphics.Color
        android.graphics.Canvas
        android.graphics.Paint
        android.graphics.Paint$Align
        android.graphics.RectF
        android.os.Handler
        android.os.Looper
        android.view.Gravity
        androidx.lifecycle.LifecycleEventObserver
        androidx.lifecycle.Lifecycle$Event)

;; --- Game Configuration ---
(def board-size 20)
(def game-speed-ms 150)

;; --- Game State & UI Atoms ---
(def game-state (atom nil))
(def score-view-atom (atom nil))
(def game-view-atom (atom nil))
(def controls-view-atom (atom nil))
(def game-handler (Handler. (Looper/getMainLooper)))
(def game-loop-runnable (atom nil))

(defn- initial-game-state []
  {:snake [[5 5] [5 4] [5 3]]
   :direction :down
   :food [10 10]
   :score 0
   :game-over? false
   :paused? true})

;; --- Utility & Game Logic ---
(defn- log-d [& args]
  (Log/d log-tag (apply str args)))

(defn- generate-food [snake]
  (let [rand-pos #(rand-int board-size)]
    (loop [new-food [(rand-pos) (rand-pos)]]
      (if (some #(= new-food %) snake)
        (recur [(rand-pos) (rand-pos)])
        new-food))))

(defn- update-game-state! []
  (swap! game-state
         (fn [{:keys [snake direction food score game-over? paused?] :as current-state}]
           (if (or game-over? paused?)
             current-state
             (let [new-head (let [[hx hy] (first snake)]
                              (case direction
                                :up [hx (dec hy)] :down [hx (inc hy)]
                                :left [(dec hx) hy] :right [(inc hx) hy]))
                   wall-collision? (not (every? #(< -1 % board-size) new-head))
                   self-collision? (some #(= new-head %) (rest snake))]
               (if (or wall-collision? self-collision?)
                 (assoc current-state :game-over? true)
                 (if (= new-head food)
                   (-> current-state
                       (update :snake #(cons new-head %))
                       (assoc :food (generate-food (cons new-head snake)))
                       (update :score inc))
                   (-> current-state
                       (update :snake #(cons new-head (vec (butlast %))))))))))))

;; --- UI Creation ---
(defn- create-game-view [context]
  (let [bg-paint (doto (Paint.) (.setColor (Color/parseColor "#2E2E2E")))
        grid-paint (doto (Paint.) (.setColor (Color/parseColor "#444444")))
        snake-paint (doto (Paint.) (.setColor (Color/parseColor "#76FF03")))
        food-paint (doto (Paint.) (.setColor (Color/parseColor "#FF4081")))
        game-over-paint (doto (Paint.) (.setColor Color/WHITE) (.setTextSize 100.0) (.setTextAlign Paint$Align/CENTER))
        final-score-paint (doto (Paint.) (.setColor Color/WHITE) (.setTextSize 60.0) (.setTextAlign Paint$Align/CENTER))]
    (proxy [View] [context]
      (onMeasure [w-spec h-spec]
        (let [width (View/getDefaultSize 0 w-spec)] (.setMeasuredDimension this width width)))
      (onDraw [^Canvas canvas]
        (proxy-super onDraw canvas)
        (let [width (.getWidth this) height (.getHeight this) cell-size (/ width board-size)]
          (.drawRect canvas 0 0 width height bg-paint)
          (dotimes [i (inc board-size)]
            (.drawLine canvas (* i cell-size) 0 (* i cell-size) height grid-paint)
            (.drawLine canvas 0 (* i cell-size) width (* i cell-size) grid-paint))
          (when-let [{:keys [snake food game-over? score]} @game-state]
            (doseq [[x y] snake] (.drawRect canvas (RectF. (* x cell-size) (* y cell-size) (* (inc x) cell-size) (* (inc y) cell-size)) snake-paint))
            (let [[fx fy] food] (.drawRect canvas (RectF. (* fx cell-size) (* fy cell-size) (* (inc fx) cell-size) (* (inc fy) cell-size)) food-paint))
            (when game-over?
              (.drawText canvas "GAME OVER" (/ width 2) (- (/ height 2) 50) game-over-paint)
              (.drawText canvas (str "Final Score: " score) (/ width 2) (+ (/ height 2) 40) final-score-paint))))))))

;; --- Event Handlers ---
(declare restart-game)
(defn- on-direction-change [new-dir opposite-dir]
  (proxy [View$OnClickListener] []
    (onClick [_]
      (swap! game-state
             (fn [current-state]
               (if (and (not (:game-over? current-state)) (not= (:direction current-state) opposite-dir))
                 (assoc current-state :direction new-dir)
                 current-state))))))

(def on-up-click (on-direction-change :up :down))
(def on-down-click (on-direction-change :down :up))
(def on-left-click (on-direction-change :left :right))
(def on-right-click (on-direction-change :right :left))
(def on-restart-click (proxy [View$OnClickListener] [] (onClick [_] (restart-game))))

(defn- create-controls [context]
  (let [layout (doto (GridLayout. context) (.setColumnCount 3) (.setRowCount 3))
        add-button (fn [text r c handler]
                     (let [button (doto (Button. context) (.setText text) (.setTextSize 24.0) (.setOnClickListener handler))
                           params (doto (GridLayout$LayoutParams. (GridLayout/spec r 1.0) (GridLayout/spec c 1.0))
                                    (.width 0) (.height 0) (.setMargins 8 8 8 8))]
                       (.addView layout button params)))]
    (.addView layout (View. context) (GridLayout$LayoutParams. (GridLayout/spec 1 1.0) (GridLayout/spec 1 1.0)))
    (add-button "↑" 0 1 on-up-click) (add-button "←" 1 0 on-left-click)
    (add-button "→" 1 2 on-right-click) (add-button "↓" 2 1 on-down-click)
    layout))

;; --- Game Lifecycle ---
(declare start-game stop-game)
(defn- create-game-loop-runnable []
  (proxy [Runnable] []
    (run []
      (update-game-state!)
      (let [state @game-state]
        (when-let [sv @score-view-atom] (.setText sv (str "Score: " (:score state))))
        (when-let [gv @game-view-atom] (.postInvalidate gv))
        (when-let [cv @controls-view-atom] (.setVisibility cv (if (:game-over? state) View/INVISIBLE View/VISIBLE)))
        (when-not (:game-over? state) (.postDelayed game-handler this game-speed-ms))))))

(defn- start-game []
  (log-d "Starting game loop")
  (swap! game-state assoc :paused? false)
  (.removeCallbacks game-handler @game-loop-runnable)
  (.postDelayed game-handler @game-loop-runnable game-speed-ms))

(defn- stop-game []
  (log-d "Stopping game loop")
  (swap! game-state assoc :paused? true)
  (.removeCallbacks game-handler @game-loop-runnable))

(defn- restart-game []
  (log-d "Restarting game")
  (when-let [cv @controls-view-atom] (.setVisibility cv View/VISIBLE))
  (reset! game-state (initial-game-state))
  (start-game))

;; --- Android Lifecycle Observer ---
(defn- handle-lifecycle-change [_ event]
  (log-d "Lifecycle event: " (.name event))
  (case (.name event)
    "ON_RESUME" (when (:paused? @game-state) (start-game))
    "ON_PAUSE" (stop-game)
    "ON_DESTROY" (stop-game)
    nil))

(defn- create-lifecycle-observer []
  (proxy [LifecycleEventObserver] [] (onStateChanged [s e] (handle-lifecycle-change s e))))

;; --- Main Entry Point ---
(defn -main []
  (log-d "Clojure -main function started.")
  (reset! game-state (initial-game-state))
  (reset! game-loop-runnable (create-game-loop-runnable))

  (let [main-layout (doto (LinearLayout. *context*) (.setOrientation LinearLayout/VERTICAL) (.setBackgroundColor (Color/parseColor "#121212")) (.setPadding 16 16 16 16))
        top-bar (doto (LinearLayout. *context*) (.setOrientation LinearLayout/HORIZONTAL) (.setGravity Gravity/CENTER_VERTICAL))
        score-view (doto (TextView. *context*) (.setText "Score: 0") (.setTextColor Color/WHITE) (.setTextSize 24.0))
        restart-button (doto (Button. *context*) (.setText "Restart") (.setOnClickListener on-restart-click))
        game-view (create-game-view *context*)
        controls-view (create-controls *context*)]

    (reset! score-view-atom score-view)
    (reset! game-view-atom game-view)
    (reset! controls-view-atom controls-view)

    (.addView top-bar score-view (LinearLayout$LayoutParams. 0 LinearLayout$LayoutParams/WRAP_CONTENT 1.0))
    (.addView top-bar restart-button (LinearLayout$LayoutParams. -2 -2))
    (.addView main-layout top-bar (LinearLayout$LayoutParams. -1 -2))
    (.addView main-layout game-view (doto (LinearLayout$LayoutParams. -1 0) (.weight 1.0) (.topMargin 16) (.bottomMargin 16)))
    (.addView main-layout controls-view (doto (LinearLayout$LayoutParams. -1 -2) (.gravity Gravity/CENTER)))
    (.addView *content-layout* main-layout))

  (try
    (let [lifecycle (.. *context* getLifecycle)]
      (log-d "Registering lifecycle observer. State: " (.. lifecycle getCurrentState name))
      (.addObserver lifecycle (create-lifecycle-observer)))
    (catch Exception e (log-d "Error registering lifecycle observer: " (.getMessage e))))
  (log-d "Clojure -main function finished setup."))

