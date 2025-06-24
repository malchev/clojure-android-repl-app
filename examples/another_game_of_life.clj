; Conway's Game of Life: interactive 20x20 grid with Play/Stop/Step/Randomize/Clear controls, grid has a black border -- NOW WITH NO DELAY BETWEEN STEPS

(import
  '[android.content Context]
  '[android.view View View$OnClickListener]
  '[android.widget LinearLayout LinearLayout$LayoutParams Button GridLayout]
  '[android.widget GridLayout$LayoutParams]
  '[android.graphics Color]
  '[android.graphics.drawable GradientDrawable]
  '[android.os Handler Looper]
  '[androidx.lifecycle LifecycleEventObserver]
  '[java.util Random])

(def TAG "ClojureApp")

(defn logd [msg]
  (android.util.Log/d TAG (str msg)))

(def grid-size 20)
(def cell-size-dp 18)
(def cell-margin-dp 1)
(def border-width-dp 2)
(def border-radius-dp 4)
(def alive-color Color/BLACK)
(def dead-color Color/WHITE)
; No delay now!
;(def play-interval-ms 500)

(defn dp->px [^Context ctx dp]
  (let [scale (.density (.getDisplayMetrics (.getResources ctx)))]
    (int (+ 0.5 (* dp scale)))))

(defonce game-atom
  (atom {:board (vec (repeat grid-size (vec (repeat grid-size false))))
         :running? false
         :handler nil
         :cell-views {}}))

(defn count-neighbors [board row col]
  (let [rows grid-size
        cols grid-size
        deltas (for [dr [-1 0 1] dc [-1 0 1] :when (not (and (= dr 0) (= dc 0)))]
                 [dr dc])]
    (count
      (filter true?
        (for [[dr dc] deltas
              :let [nr (+ row dr) nc (+ col dc)]]
          (when (and (>= nr 0) (< nr rows) (>= nc 0) (< nc cols))
            (get-in board [nr nc])))))))

(defn next-cell [alive? n]
  (or (and alive? (or (= n 2) (= n 3)))
      (and (not alive?) (= n 3))))

(defn step-board [board]
  (vec
    (for [r (range grid-size)]
      (vec
        (for [c (range grid-size)]
          (let [n (count-neighbors board r c)
                alive? (get-in board [r c])]
            (next-cell alive? n)))))))

(defn all-dead? [board]
  (not-any? true? (apply concat board)))

(defn update-cell-view! [cell-view alive?]
  (.setBackgroundColor cell-view (if alive? alive-color dead-color)))

(defn update-board-views! [board cell-views]
  (doseq [r (range grid-size)
          c (range grid-size)]
    (let [cell-view (get cell-views [r c])]
      (when cell-view
        (update-cell-view! cell-view (get-in board [r c]))))))

(defn toggle-cell! [row col]
  (swap! game-atom
    (fn [st]
      (let [prev-state (get-in st [:board row col])
            new-state (not prev-state)
            new-board (assoc-in (:board st) [row col] new-state)
            cell-view (get-in st [:cell-views [row col]])]
        (when cell-view
          (update-cell-view! cell-view new-state))
        (assoc st :board new-board)))))

(defn run-game-loop! []
  (letfn [(loop-step []
            (let [{:keys [board running? handler cell-views]} @game-atom]
              (logd (str "Game loop step, running? " running?))
              (when running?
                (let [next-board (step-board board)]
                  (swap! game-atom assoc :board next-board)
                  (update-board-views! next-board cell-views)
                  (if (all-dead? next-board)
                    (do
                      (logd "All cells dead, stopping game.")
                      (swap! game-atom assoc :running? false))
                    ;; No delay: immediately post the next step to the UI thread
                    (.post handler loop-step))))))]
    (loop-step)))

(defn start-game! []
  (swap! game-atom assoc :running? true)
  (logd "Play pressed: starting game loop.")
  (run-game-loop!))

(defn stop-game! []
  (swap! game-atom update :handler (fn [h]
                                     (when h (.removeCallbacksAndMessages h nil))
                                     h))
  (swap! game-atom assoc :running? false)
  (logd "Stop pressed: game stopped."))

(defn step-once! []
  (let [{:keys [board cell-views]} @game-atom
        next-board (step-board board)]
    (swap! game-atom assoc :board next-board)
    (update-board-views! next-board cell-views)
    (logd "Step pressed: advanced one generation.")))

(defn randomize-board! []
  (let [rnd (Random.)
        random-board (vec
                       (for [r (range grid-size)]
                         (vec
                           (for [c (range grid-size)]
                             (.nextBoolean rnd)))))
        cell-views (:cell-views @game-atom)]
    (swap! game-atom assoc :board random-board)
    (update-board-views! random-board cell-views)
    (logd "Randomize pressed: randomized board.")))

(defn clear-board! []
  (let [empty-board (vec (repeat grid-size (vec (repeat grid-size false))))
        cell-views (:cell-views @game-atom)]
    (swap! game-atom assoc :board empty-board)
    (update-board-views! empty-board cell-views)
    (logd "Clear pressed: cleared board.")))

(defn handle-lifecycle [event]
  (logd (str "Lifecycle event: " event))
  (case (.name event)
    "ON_STOP" (stop-game!)
    nil))

(defn make-lifecycle-observer []
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (handle-lifecycle event))))

(defn make-grid [^Context ctx]
  (let [grid (GridLayout. ctx)
        cell-size (dp->px ctx cell-size-dp)
        margin (dp->px ctx cell-margin-dp)]
    (.setRowCount grid grid-size)
    (.setColumnCount grid grid-size)
    (let [cell-views
          (into {}
            (for [r (range grid-size)
                  c (range grid-size)]
              (let [cell (View. ctx)
                    params (GridLayout$LayoutParams.)]
                (set! (.width params) cell-size)
                (set! (.height params) cell-size)
                (.setMargins params margin margin margin margin)
                (.setLayoutParams cell params)
                (.setBackgroundColor cell dead-color)
                (.setOnClickListener cell
                  (reify View$OnClickListener
                    (onClick [_ v]
                      (logd (str "Cell tapped: " r "," c))
                      (toggle-cell! r c))))
                (.addView grid cell)
                [[r c] cell])))]
      [grid cell-views])))

(defn make-grid-border-wrapper [^Context ctx grid]
  (let [border-width (dp->px ctx border-width-dp)
        border-radius (dp->px ctx border-radius-dp)
        wrapper (LinearLayout. ctx)
        params (android.widget.LinearLayout$LayoutParams.
                 android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                 android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
        drawable (GradientDrawable.)]
    (.setOrientation wrapper LinearLayout/VERTICAL)
    (.setColor drawable dead-color)
    (.setStroke drawable border-width alive-color)
    (.setCornerRadius drawable (float border-radius))
    (.setBackground wrapper drawable)
    (let [pad border-width]
      (.setPadding wrapper pad pad pad pad))
    (.addView wrapper grid)
    wrapper))

(defn make-controls [^Context ctx]
  (let [lp (android.widget.LinearLayout$LayoutParams.
             android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
             android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)]
    (doto (LinearLayout. ctx)
      (.setOrientation LinearLayout/HORIZONTAL)
      (.addView
        (doto (Button. ctx)
          (.setText "Play")
          (.setOnClickListener
            (reify View$OnClickListener
              (onClick [_ v]
                (when-not (:running? @game-atom)
                  (start-game!)))))))
      (.addView
        (doto (Button. ctx)
          (.setText "Stop")
          (.setOnClickListener
            (reify View$OnClickListener
              (onClick [_ v]
                (stop-game!))))))
      (.addView
        (doto (Button. ctx)
          (.setText "Step")
          (.setOnClickListener
            (reify View$OnClickListener
              (onClick [_ v]
                (when-not (:running? @game-atom)
                  (step-once!)))))))
      (.addView
        (doto (Button. ctx)
          (.setText "Randomize")
          (.setOnClickListener
            (reify View$OnClickListener
              (onClick [_ v]
                (when-not (:running? @game-atom)
                  (randomize-board!)))))))
      (.addView
        (doto (Button. ctx)
          (.setText "Clear")
          (.setOnClickListener
            (reify View$OnClickListener
              (onClick [_ v]
                (when-not (:running? @game-atom)
                  (clear-board!))))))))))

(defn -main []
  (logd "App started: initializing UI.")
  (let [ctx *context*
        layout *content-layout*
        [grid cell-views] (make-grid ctx)
        grid-wrapper (make-grid-border-wrapper ctx grid)
        controls (make-controls ctx)
        handler (Handler. (Looper/getMainLooper))]
    (logd "Storing cell-views and handler in atom.")
    (swap! game-atom assoc :cell-views cell-views :handler handler)
    (.removeAllViews layout)
    (.setOrientation layout LinearLayout/VERTICAL)
    (let [lp-grid (android.widget.LinearLayout$LayoutParams.
                    android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                    android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)]
      (.addView layout grid-wrapper lp-grid)
      (.addView layout controls))
    (let [lifecycle (.. ctx (getLifecycle))
          observer (make-lifecycle-observer)]
      (try
        (logd (str "Current lifecycle state: " (.. lifecycle (getCurrentState))))
        (.addObserver lifecycle observer)
        (catch Exception e
          (logd (str "Lifecycle observer registration failed: " e)))))
    (logd "UI initialized.")))
