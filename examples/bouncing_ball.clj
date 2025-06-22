;; Bouncing ball physics simulation with animated graphics

(import '[android.widget LinearLayout TextView]
        '[android.view View ViewGroup$LayoutParams]
        '[android.graphics Canvas Paint Color]
        '[android.content Context]
        '[android.util Log]
        '[android.os Handler Looper]
        '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event])

(def TAG "ClojureApp")

(defn debug-log [msg]
  (Log/d TAG (str msg)))

;; Ball physics state
(def ball-state (atom {:x 200.0
                       :y 200.0
                       :vx 8.0
                       :vy 5.0
                       :radius 30.0
                       :gravity 0.5
                       :bounce-damping 0.8}))

(defn create-ball-view [context]
  (let [paint (Paint.)
        handler (Handler. (Looper/getMainLooper))
        animation-runnable (atom nil)]
    
    ;; Configure paint
    (.setColor paint Color/RED)
    (.setAntiAlias paint true)
    
    (proxy [View] [context]
      (onDraw [canvas]
        (let [{:keys [x y radius]} @ball-state
              width (.getWidth this)
              height (.getHeight this)]
          ;; Clear background
          (.drawColor canvas Color/WHITE)
          ;; Draw ball
          (.drawCircle canvas (float x) (float y) (float radius) paint)))
      
      (onSizeChanged [w h old-w old-h]
        (debug-log (str "View size changed: " w "x" h))
        ;; Start animation when view is sized
        (when (and (> w 0) (> h 0))
          (let [update-fn
                (fn update-ball []
                  (let [{:keys [x y vx vy radius gravity bounce-damping]} @ball-state
                        width (.getWidth this)
                        height (.getHeight this)]
                    (when (and (> width 0) (> height 0))
                      ;; Update physics
                      (let [new-vy (+ vy gravity)
                            new-x (+ x vx)
                            new-y (+ y new-vy)
                            
                            ;; Bounce off walls
                            [final-x final-vx] (cond
                                                  (<= new-x radius) [radius (- (Math/abs vx))]
                                                  (>= new-x (- width radius)) [(- width radius) (- (Math/abs vx))]
                                                  :else [new-x vx])
                            
                            [final-y final-vy] (cond
                                                  (<= new-y radius) [radius (* (- (Math/abs new-vy)) bounce-damping)]
                                                  (>= new-y (- height radius)) [(- height radius) (* (- (Math/abs new-vy)) bounce-damping)]
                                                  :else [new-y new-vy])]
                        
                        ;; Update state
                        (swap! ball-state assoc
                               :x final-x
                               :y final-y
                               :vx final-vx
                               :vy final-vy)
                        
                        ;; Redraw
                        (.invalidate this)
                        
                        ;; Schedule next frame
                        (.postDelayed handler @animation-runnable 16)))))] ; ~60 FPS
            
            (reset! animation-runnable update-fn)
            (.post handler @animation-runnable)))))))

(defn create-lifecycle-observer []
  (proxy [LifecycleEventObserver] []
    (onStateChanged [source event]
      (let [event-name (.name event)]
        (debug-log (str "Lifecycle event: " event-name))
        (case event-name
          "ON_CREATE" (debug-log "App created")
          "ON_START" (debug-log "App started")
          "ON_RESUME" (debug-log "App resumed")
          "ON_PAUSE" (debug-log "App paused")
          "ON_STOP" (debug-log "App stopped")
          "ON_DESTROY" (debug-log "App destroyed")
          (debug-log (str "Unknown lifecycle event: " event-name)))))))

(defn -main []
  (debug-log "Starting bouncing ball app")
  
  (try
    ;; Create main layout
    (let [main-layout (LinearLayout. *context*)
          ball-view (create-ball-view *context*)
          layout-params (android.widget.LinearLayout$LayoutParams.
                         ViewGroup$LayoutParams/MATCH_PARENT
                         ViewGroup$LayoutParams/MATCH_PARENT)]
      
      ;; Configure main layout
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setBackgroundColor main-layout Color/WHITE)
      
      ;; Add ball view
      (.addView main-layout ball-view layout-params)
      
      ;; Set up lifecycle observer
      (let [lifecycle (.getLifecycle *context*)
            observer (create-lifecycle-observer)]
        (debug-log (str "Current lifecycle state: " (.getCurrentState lifecycle)))
        (.addObserver lifecycle observer))
      
      ;; Set content view
      (.setContentView *context* main-layout)
      
      (debug-log "Bouncing ball app initialized successfully"))
    
    (catch Exception e
      (debug-log (str "Error in main: " (.getMessage e)))
      (.printStackTrace e))))
