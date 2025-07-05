;; A Conway's Game of Life implementation for Android in Clojure.
(import android.content.Context)
(import android.graphics.Color)
(import android.graphics.drawable.GradientDrawable)
(import android.os.Handler)
(import android.os.Looper)
(import android.util.Log)
(import android.view.View)
(import android.view.View$OnClickListener)
(import android.view.Gravity)
(import android.widget.Button)
(import android.widget.GridLayout)
(import android.widget.GridLayout$LayoutParams)
(import android.widget.TextView)
(import android.widget.LinearLayout)
(import android.widget.LinearLayout$LayoutParams)
(import androidx.lifecycle.Lifecycle$Event)
(import androidx.lifecycle.LifecycleEventObserver)

(def ^:const LOG_TAG "ClojureApp")
(def ^:const GRID_SIZE 20)
(def ^:const DELAY_MS 500)

;; --- State Management ---
(defonce grid-state (atom (vec (repeat GRID_SIZE (vec (repeat GRID_SIZE false))))))
(defonce grid-views (atom (vec (repeat GRID_SIZE (vec (repeat GRID_SIZE nil))))))
(defonce is-running (atom false))
(defonce game-handler (Handler. (Looper/getMainLooper)))

;; --- Utility & Debugging ---
(defn log-debug [& msgs]
  (Log/d LOG_TAG (apply str msgs)))

(defn dp-to-px [dp]
  (let [metrics (.. *context* getResources getDisplayMetrics)]
    (int (* dp (.-density metrics)))))

;; --- UI Update Functions ---
(defn update-cell-color! [^View view is-alive?]
  (let [color (if is-alive? Color/BLACK Color/WHITE)]
    (.setBackgroundColor view color)))

(defn update-all-colors! []
  (log-debug "Updating all cell colors.")
  (let [current-grid @grid-state
        views @grid-views]
    (dotimes [y GRID_SIZE]
      (dotimes [x GRID_SIZE]
        (when-let [cell-view (get-in views [y x])]
          (update-cell-color! cell-view (get-in current-grid [y x])))))))

;; --- Game Logic Functions ---
(defn get-live-neighbors [grid x y]
  (reduce +
    (for [dx [-1 0 1]
          dy [-1 0 1]
          :when (not (and (zero? dx) (zero? dy)))]
      (let [nx (+ x dx)
            ny (+ y dy)]
        (if (and (>= nx 0) (< nx GRID_SIZE)
                 (>= ny 0) (< ny GRID_SIZE)
                 (get-in grid [ny nx]))
          1
          0)))))

(defn compute-next-state [current-grid]
  (vec
    (for [y (range GRID_SIZE)]
      (vec
        (for [x (range GRID_SIZE)]
          (let [alive? (get-in current-grid [y x])
                neighbors (get-live-neighbors current-grid x y)]
            (cond
              (and alive? (< neighbors 2)) false ; Underpopulation
              (and alive? (or (= neighbors 2) (= neighbors 3))) true ; Survival
              (and alive? (> neighbors 3)) false ; Overpopulation
              (and (not alive?) (= neighbors 3)) true ; Reproduction
              :else alive?)))))))

(defn perform-step! []
  (log-debug "Performing step")
  (let [current-grid @grid-state
        next-grid (compute-next-state current-grid)]
    (reset! grid-state next-grid)
    (update-all-colors!)
    (when-not (some true? (flatten next-grid))
      (log-debug "Game over: all cells are dead. Stopping simulation.")
      (reset! is-running false))))

;; --- Event Handlers ---
(def game-loop-fn (atom nil))

(defn handle-cell-click [^View view y x]
  (when-not @is-running
    (log-debug "Cell clicked at [" y ", " x "]")
    (let [new-state (not (get-in @grid-state [y x]))]
      (swap! grid-state assoc-in [y x] new-state)
      (update-cell-color! view new-state))))

(defn handle-play-click [_]
  (log-debug "Play button clicked.")
  (when-not @is-running
    (reset! is-running true)
    (.post game-handler @game-loop-fn)))

(defn handle-stop-click [_]
  (log-debug "Stop button clicked.")
  (reset! is-running false))

(defn handle-step-click [_]
  (log-debug "Step button clicked.")
  (when-not @is-running
    (perform-step!)))

(defn handle-random-click [_]
  (log-debug "Random button clicked.")
  (when-not @is-running
    (let [random-grid (vec (for [_ (range GRID_SIZE)]
                             (vec (for [_ (range GRID_SIZE)]
                                    (> (rand) 0.7)))))] ; ~30% chance of being alive
      (reset! grid-state random-grid)
      (update-all-colors!))))

(defn handle-clear-click [_]
  (log-debug "Clear button clicked.")
  (when-not @is-running
    (let [empty-grid (vec (repeat GRID_SIZE (vec (repeat GRID_SIZE false))))]
      (reset! grid-state empty-grid)
      (update-all-colors!))))

;; --- Lifecycle Management ---
(defn create-lifecycle-observer [handler]
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (log-debug "Lifecycle event: " (.name event))
      (condp = (.name event)
        "ON_STOP"
        (do (log-debug "ON_STOP: Stopping game simulation.")
            (reset! is-running false))

        "ON_DESTROY"
        (do (log-debug "ON_DESTROY: Cleaning up handler callbacks.")
            (.removeCallbacksAndMessages handler nil))
        
        nil))))

;; --- Main App Entry Point ---
(defn -main []
  (log-debug "App starting, creating UI.")
  
  (reset! game-loop-fn
    (fn []
      (when @is-running
        (perform-step!)
        (when @is-running
          (.postDelayed game-handler @game-loop-fn DELAY_MS)))))

  (let [main-layout (LinearLayout. *context*)
        _ (.setOrientation main-layout LinearLayout/VERTICAL)
        _ (.setGravity main-layout Gravity/CENTER_HORIZONTAL)

        grid-layout (GridLayout. *context*)
        _ (.setColumnCount grid-layout GRID_SIZE)
        _ (.setRowCount grid-layout GRID_SIZE)
        border-drawable (doto (GradientDrawable.)
                          (.setStroke (dp-to-px 2) Color/BLACK)
                          (.setColor Color/TRANSPARENT))
        _ (.setBackground grid-layout border-drawable)

        cell-size (dp-to-px 16)
        cell-margin (dp-to-px 1)
        
        control-button-layout (LinearLayout. *context*)
        _ (.setOrientation control-button-layout LinearLayout/HORIZONTAL)
        _ (.setGravity control-button-layout Gravity/CENTER)
        
        setup-button-layout (LinearLayout. *context*)
        _ (.setOrientation setup-button-layout LinearLayout/HORIZONTAL)
        _ (.setGravity setup-button-layout Gravity/CENTER)
        
        play-button (Button. *context*)
        stop-button (Button. *context*)
        step-button (Button. *context*)
        random-button (Button. *context*)
        clear-button (Button. *context*)
        
        lifecycle (.. *context* getLifecycle)
        observer (create-lifecycle-observer game-handler)]

    (dotimes [y GRID_SIZE]
      (dotimes [x GRID_SIZE]
        (let [cell (TextView. *context*)
              params (GridLayout$LayoutParams.)]
          (set! (.-width params) cell-size)
          (set! (.-height params) cell-size)
          (.setMargins params cell-margin cell-margin cell-margin cell-margin)
          (.setLayoutParams cell params)
          (update-cell-color! cell (get-in @grid-state [y x]))
          (.setOnClickListener cell (proxy [View$OnClickListener] [] (onClick [v] (handle-cell-click v y x))))
          (swap! grid-views assoc-in [y x] cell)
          (.addView grid-layout cell))))

    (.addView main-layout grid-layout)

    (doto play-button (.setText "Play") (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (handle-play-click v)))))
    (doto stop-button (.setText "Stop") (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (handle-stop-click v)))))
    (doto step-button (.setText "Step") (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (handle-step-click v)))))
    (doto random-button (.setText "Random") (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (handle-random-click v)))))
    (doto clear-button (.setText "Clear") (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (handle-clear-click v)))))

    (let [btn-params (LinearLayout$LayoutParams. LinearLayout$LayoutParams/WRAP_CONTENT LinearLayout$LayoutParams/WRAP_CONTENT)]
      (.addView control-button-layout play-button btn-params)
      (.addView control-button-layout stop-button btn-params)
      (.addView control-button-layout step-button btn-params)
      (.addView setup-button-layout random-button btn-params)
      (.addView setup-button-layout clear-button btn-params))
    
    (let [control-layout-params (LinearLayout$LayoutParams. LinearLayout$LayoutParams/MATCH_PARENT LinearLayout$LayoutParams/WRAP_CONTENT)
          setup-layout-params (LinearLayout$LayoutParams. LinearLayout$LayoutParams/MATCH_PARENT LinearLayout$LayoutParams/WRAP_CONTENT)]
      (.setMargins control-layout-params 0 (dp-to-px 10) 0 0)
      (.addView main-layout control-button-layout control-layout-params)
      (.addView main-layout setup-button-layout setup-layout-params))

    (.addView *content-layout* main-layout)
    
    (try
      (.addObserver lifecycle observer)
      (log-debug "Lifecycle observer registered successfully.")
      (catch Exception e
        (log-debug "Error registering lifecycle observer: " (.getMessage e))))
    
    (log-debug "UI setup complete.")))
