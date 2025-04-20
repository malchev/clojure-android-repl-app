; Conway's Game of Life - 20x20 grid with play/stop/step and debug logging

(import
  [android.content Context]
  [android.util Log]
  [android.view View View$OnClickListener]
  [android.widget LinearLayout Button GridLayout]
  [android.graphics Color]
  [android.os Handler Looper]
  [androidx.lifecycle LifecycleEventObserver Lifecycle$Event])

;; ---- CONSTANTS ----
(def TAG "ClojureApp")
(def grid-size 20)
(def alive-color Color/BLACK)
(def dead-color Color/WHITE)
(def cell-size-dp 18)
(def cell-margin-dp 1)
(def play-interval-ms 500)

;; ---- UTILS ----
(defn dp->px [^Context ctx dp]
  (let [scale (.density (.getDisplayMetrics (.getResources ctx)))]
    (int (+ 0.5 (* dp scale)))))

(defn debug-log [msg]
  (Log/d TAG (str msg)))

;; ---- STATE ----
(def state-atom (atom (vec (repeat grid-size (vec (repeat grid-size false))))))
(def cell-views (atom nil)) ; 2D vector of View objects
(def playing-atom (atom false))
(def play-handler (atom nil)) ; Handler for play loop

;; ---- GAME LOGIC ----
(defn neighbors [x y]
  (for [dx [-1 0 1]
        dy [-1 0 1]
        :when (not (and (= dx 0) (= dy 0)))
        :let [nx (+ x dx)
              ny (+ y dy)]
        :when (and (>= nx 0) (< nx grid-size)
                   (>= ny 0) (< ny grid-size))]
    [nx ny]))

(defn alive-neighbors [state x y]
  (count (filter #(get-in state %) (neighbors x y))))

(defn step-state [state]
  (vec
    (for [y (range grid-size)]
      (vec
        (for [x (range grid-size)]
          (let [alive? (get-in state [y x])
                n (alive-neighbors state x y)]
            (cond
              (and alive? (or (= n 2) (= n 3))) true
              (and (not alive?) (= n 3)) true
              :else false)))))))

(defn all-dead? [state]
  (every? (fn [row] (every? not row)) state))

(defn set-cell-bg! [^View v alive?]
  (.setBackgroundColor v (if alive? alive-color dead-color)))

(defn update-grid-ui! []
  (let [views @cell-views
        state @state-atom]
    (doseq [y (range grid-size)
            x (range grid-size)]
      (set-cell-bg! (get-in views [y x])
                    (get-in state [y x])))))

(defn toggle-cell! [x y]
  (swap! state-atom update-in [y x] not)
  (update-grid-ui!))

;; ---- PLAY/STOP/STEP ----
(defn play-step! []
  (let [old-state @state-atom
        new-state (step-state old-state)]
    (reset! state-atom new-state)
    (update-grid-ui!)
    (debug-log "play-step! advanced state.")
    new-state))

(defn play-loop! [^Handler handler]
  (when @playing-atom
    (let [new-state (play-step!)]
      (if (or (all-dead? new-state) (not @playing-atom))
        (do
          (reset! playing-atom false)
          (debug-log "play-loop! stopped (all dead or stopped)"))
        (.postDelayed handler
                      #(play-loop! handler)
                      play-interval-ms)))))

(defn start-play! [^Context ctx]
  (debug-log "start-play! called")
  (when-not @playing-atom
    (reset! playing-atom true)
    (let [handler (Handler. (Looper/getMainLooper))]
      (reset! play-handler handler)
      (play-loop! handler))))

(defn stop-play! []
  (debug-log "stop-play! called")
  (reset! playing-atom false))

(defn step-once! []
  (debug-log "step-once! called")
  (when-not @playing-atom
    (play-step!)))

;; ---- BUTTON HANDLERS ----
(defn play-handler-fn [ctx v]
  (debug-log "Play button pressed")
  (start-play! ctx))

(defn stop-handler-fn [v]
  (debug-log "Stop button pressed")
  (stop-play!))

(defn step-handler-fn [v]
  (debug-log "Step button pressed")
  (step-once!))

;; ---- MAIN ENTRY ----
(defn -main []
  (let [ctx (deref (resolve '*context*))
        root (deref (resolve '*content-layout*))
        px (fn [dp] (dp->px ctx dp))]
    ;; Main vertical layout
    (let [main-layout (LinearLayout. ctx)
          grid-layout (GridLayout. ctx)
          ;; Build cells and views vector
          cells (vec
                  (for [y (range grid-size)]
                    (vec
                      (for [x (range grid-size)]
                        (let [cell-view (View. ctx)
                              lp (android.widget.LinearLayout$LayoutParams.
                                   (px cell-size-dp) (px cell-size-dp))]
                          (.setMargins lp (px cell-margin-dp) (px cell-margin-dp)
                                       (px cell-margin-dp) (px cell-margin-dp))
                          (.setLayoutParams cell-view lp)
                          (.setBackgroundColor cell-view dead-color)
                          (.setOnClickListener cell-view
                            (proxy [View$OnClickListener] []
                              (onClick [v]
                                (debug-log (str "Cell tapped " x "," y))
                                (toggle-cell! x y))))
                          (.addView grid-layout cell-view)
                          cell-view)))))]
      (reset! cell-views cells)
      ;; Controls
      (let [controls-layout (LinearLayout. ctx)
            btn-play (Button. ctx)
            btn-stop (Button. ctx)
            btn-step (Button. ctx)
            btn-lp (android.widget.LinearLayout$LayoutParams.
                     0 (android.widget.LinearLayout$LayoutParams/WRAP_CONTENT) 1.0)
            lp-grid (android.widget.LinearLayout$LayoutParams.
                      (android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                      (android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
            lp-controls (android.widget.LinearLayout$LayoutParams.
                          (android.widget.LinearLayout$LayoutParams/MATCH_PARENT)
                          (android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))]
        (.setOrientation main-layout LinearLayout/VERTICAL)
        (.setColumnCount grid-layout grid-size)
        (.setRowCount grid-layout grid-size)
        (.setOrientation controls-layout LinearLayout/HORIZONTAL)
        (.setText btn-play "Play")
        (.setText btn-stop "Stop")
        (.setText btn-step "Step")
        (.setTextColor btn-play Color/BLACK)
        (.setTextColor btn-stop Color/BLACK)
        (.setTextColor btn-step Color/BLACK)
        (.setBackgroundColor btn-play (Color/parseColor "#E0FFE0"))
        (.setBackgroundColor btn-stop (Color/parseColor "#FFE0E0"))
        (.setBackgroundColor btn-step (Color/parseColor "#E0E0FF"))
        (.setLayoutParams btn-play btn-lp)
        (.setLayoutParams btn-stop btn-lp)
        (.setLayoutParams btn-step btn-lp)
        (.setOnClickListener btn-play
          (proxy [View$OnClickListener] []
            (onClick [v] (play-handler-fn ctx v))))
        (.setOnClickListener btn-stop
          (proxy [View$OnClickListener] []
            (onClick [v] (stop-handler-fn v))))
        (.setOnClickListener btn-step
          (proxy [View$OnClickListener] []
            (onClick [v] (step-handler-fn v))))
        (.addView controls-layout btn-play)
        (.addView controls-layout btn-stop)
        (.addView controls-layout btn-step)
        (.setMargins lp-controls (px 4) (px 8) (px 4) (px 8))
        ;; Compose layout
        (.addView main-layout grid-layout lp-grid)
        (.addView main-layout controls-layout lp-controls)
        (.addView root main-layout)
        ;; Initial UI sync
        (update-grid-ui!)
        ;; Lifecycle observer
        (let [lifecycle (.. ctx (getLifecycle))
              observer (proxy [LifecycleEventObserver] []
                         (onStateChanged [source event]
                           (debug-log (str "Lifecycle event: " (.name event)))))]
          (try
            (debug-log (str "Registering lifecycle observer; current state: "
                            (.. lifecycle (getCurrentState) (name))))
            (.addObserver lifecycle observer)
            (catch Exception e
              (debug-log (str "Lifecycle observer registration failed: " e)))))))))