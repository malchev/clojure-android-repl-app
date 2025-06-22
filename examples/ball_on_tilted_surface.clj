;; Accelerometer-controlled bouncing ball that responds to device tilt

(import '[android.widget LinearLayout TextView]
        '[android.view View ViewGroup$LayoutParams]
        '[android.graphics Canvas Paint Color]
        '[android.content Context]
        '[android.util Log]
        '[android.os Handler Looper]
        '[android.hardware Sensor SensorManager SensorEventListener SensorEvent]
        '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event])

(def TAG "ClojureApp")

(defn debug-log [msg]
  (Log/d TAG (str msg)))

;; Ball physics state with accelerometer data
(def ball-state (atom {:x 200.0
                       :y 200.0
                       :vx 0.0
                       :vy 0.0
                       :radius 30.0
                       :accel-x 0.0
                       :accel-y 0.0
                       :bounce-damping 0.8
                       :friction 0.98
                       :accel-sensitivity 0.5}))

;; Sensor state
(def sensor-state (atom {:sensor-manager nil
                         :accelerometer nil
                         :sensor-listener nil}))

(defn create-sensor-listener []
  (proxy [SensorEventListener] []
    (onSensorChanged [event]
      (when (= (.getType (.sensor event)) Sensor/TYPE_ACCELEROMETER)
        (let [values (.values event)
              ;; Invert X and Y to make tilt intuitive
              accel-x (- (aget values 0))  ; Left/right tilt
              accel-y (aget values 1)]     ; Forward/back tilt
          (swap! ball-state assoc
                 :accel-x accel-x
                 :accel-y accel-y))))
    
    (onAccuracyChanged [sensor accuracy]
      (debug-log (str "Sensor accuracy changed: " accuracy)))))

(defn setup-accelerometer [context]
  (try
    (let [sensor-manager (.getSystemService context Context/SENSOR_SERVICE)
          accelerometer (.getDefaultSensor sensor-manager Sensor/TYPE_ACCELEROMETER)]
      (if accelerometer
        (let [listener (create-sensor-listener)]
          (swap! sensor-state assoc
                 :sensor-manager sensor-manager
                 :accelerometer accelerometer
                 :sensor-listener listener)
          (debug-log "Accelerometer setup complete"))
        (debug-log "No accelerometer found on device")))
    (catch Exception e
      (debug-log (str "Error setting up accelerometer: " (.getMessage e))))))

(defn start-sensor-listening []
  (try
    (let [{:keys [sensor-manager accelerometer sensor-listener]} @sensor-state]
      (when (and sensor-manager accelerometer sensor-listener)
        (.registerListener sensor-manager sensor-listener accelerometer SensorManager/SENSOR_DELAY_GAME)
        (debug-log "Started accelerometer listening")))
    (catch Exception e
      (debug-log (str "Error starting sensor: " (.getMessage e))))))

(defn stop-sensor-listening []
  (try
    (let [{:keys [sensor-manager sensor-listener]} @sensor-state]
      (when (and sensor-manager sensor-listener)
        (.unregisterListener sensor-manager sensor-listener)
        (debug-log "Stopped accelerometer listening")))
    (catch Exception e
      (debug-log (str "Error stopping sensor: " (.getMessage e))))))

(defn create-ball-view [context]
  (let [paint (Paint.)
        handler (Handler. (Looper/getMainLooper))
        animation-runnable (atom nil)]
    
    ;; Configure paint
    (.setColor paint Color/BLUE)
    (.setAntiAlias paint true)
    
    (proxy [View] [context]
      (onDraw [canvas]
        (let [{:keys [x y radius]} @ball-state
              width (.getWidth this)
              height (.getHeight this)]
          ;; Clear background with light gray
          (.drawColor canvas Color/LTGRAY)
          ;; Draw ball
          (.drawCircle canvas (float x) (float y) (float radius) paint)))
      
      (onSizeChanged [w h old-w old-h]
        (debug-log (str "View size changed: " w "x" h))
        ;; Start animation when view is sized
        (when (and (> w 0) (> h 0))
          (let [update-fn
                (fn update-ball []
                  (let [{:keys [x y vx vy radius accel-x accel-y bounce-damping friction accel-sensitivity]} @ball-state
                        width (.getWidth this)
                        height (.getHeight this)]
                    (when (and (> width 0) (> height 0))
                      ;; Apply accelerometer forces to velocity
                      (let [new-vx (+ (* vx friction) (* accel-x accel-sensitivity))
                            new-vy (+ (* vy friction) (* accel-y accel-sensitivity))
                            
                            ;; Update position
                            new-x (+ x new-vx)
                            new-y (+ y new-vy)
                            
                            ;; Bounce off walls
                            [final-x final-vx] (cond
                                                  (<= new-x radius) 
                                                  [radius (* (- (Math/abs new-vx)) bounce-damping)]
                                                  (>= new-x (- width radius)) 
                                                  [(- width radius) (* (- (Math/abs new-vx)) bounce-damping)]
                                                  :else [new-x new-vx])
                            
                            [final-y final-vy] (cond
                                                  (<= new-y radius) 
                                                  [radius (* (- (Math/abs new-vy)) bounce-damping)]
                                                  (>= new-y (- height radius)) 
                                                  [(- height radius) (* (- (Math/abs new-vy)) bounce-damping)]
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
          "ON_RESUME" (do
                        (debug-log "App resumed - starting sensors")
                        (start-sensor-listening))
          "ON_PAUSE" (do
                       (debug-log "App paused - stopping sensors")
                       (stop-sensor-listening))
          "ON_STOP" (debug-log "App stopped")
          "ON_DESTROY" (debug-log "App destroyed")
          (debug-log (str "Unknown lifecycle event: " event-name)))))))

(defn -main []
  (debug-log "Starting accelerometer ball app")
  
  (try
    ;; Setup accelerometer
    (setup-accelerometer *context*)
    
    ;; Create main layout
    (let [main-layout (LinearLayout. *context*)
          ball-view (create-ball-view *context*)
          layout-params (android.widget.LinearLayout$LayoutParams.
                         ViewGroup$LayoutParams/MATCH_PARENT
                         ViewGroup$LayoutParams/MATCH_PARENT)]
      
      ;; Configure main layout
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setBackgroundColor main-layout Color/LTGRAY)
      
      ;; Add ball view
      (.addView main-layout ball-view layout-params)
      
      ;; Set up lifecycle observer
      (let [lifecycle (.getLifecycle *context*)
            observer (create-lifecycle-observer)]
        (debug-log (str "Current lifecycle state: " (.getCurrentState lifecycle)))
        (.addObserver lifecycle observer))
      
      ;; Set content view
      (.setContentView *context* main-layout)
      
      (debug-log "Accelerometer ball app initialized successfully"))
    
    (catch Exception e
      (debug-log (str "Error in main: " (.getMessage e)))
      (.printStackTrace e))))
