    ;; A visual Android compass app with a disc that points north using device sensors.
    
    ;; Import necessary Android classes
    (import '[android.content Context]
            '[android.hardware Sensor SensorEvent SensorEventListener SensorManager]
            '[android.widget LinearLayout]
            '[android.graphics Color Canvas Paint Paint$Style Paint$Align Path]
            '[android.view View Gravity]
            '[android.util Log]
            '[androidx.lifecycle LifecycleEventObserver Lifecycle$Event])
    
    ;; Define a constant for Logcat tag
    (def ^String LOG_TAG "ClojureApp")
    
    ;; Utility function for debug logging
    (defn log-debug [msg & args]
      (Log/d LOG_TAG (apply format msg args)))
    
    ;; Global variables to hold sensor-related objects and data
    (def sensor-manager (atom nil))
    (def accelerometer (atom nil))
    (def magnetometer (atom nil))
    (def compass-view (atom nil))
    
    ;; Float arrays to store sensor readings and calculation results
    (def gravity-readings (atom (float-array 3)))
    (def geomagnetic-readings (atom (float-array 3)))
    (def rotation-matrix (atom (float-array 9)))
    (def orientation-angles (atom (float-array 3)))
    
    ;; Current azimuth angle in degrees
    (def current-azimuth (atom 0.0))
    
    ;; Flag to track if we have valid sensor data
    (def has-gravity-data (atom false))
    (def has-geomagnetic-data (atom false))
    
    ;; Custom compass view class
    (defn create-compass-view [context]
      (let [paint-circle (Paint.)
            paint-needle (Paint.)
            paint-north (Paint.)
            paint-text (Paint.)]
        
        ;; Configure paints
        (.setColor paint-circle Color/BLACK)
        (.setStyle paint-circle Paint$Style/STROKE)
        (.setStrokeWidth paint-circle 8.0)
        (.setAntiAlias paint-circle true)
        
        (.setColor paint-needle Color/RED)
        (.setStyle paint-needle Paint$Style/FILL)
        (.setStrokeWidth paint-needle 6.0)
        (.setAntiAlias paint-needle true)
        
        (.setColor paint-north Color/BLUE)
        (.setStyle paint-north Paint$Style/FILL)
        (.setTextSize paint-north 32.0)
        (.setAntiAlias paint-north true)
        (.setTextAlign paint-north Paint$Align/CENTER)
        
        (.setColor paint-text Color/BLACK)
        (.setStyle paint-text Paint$Style/FILL)
        (.setTextSize paint-text 24.0)
        (.setAntiAlias paint-text true)
        (.setTextAlign paint-text Paint$Align/CENTER)
        
        (proxy [View] [context]
          (onDraw [canvas]
            (let [width (.getWidth this)
                  height (.getHeight this)
                  center-x (/ width 2.0)
                  center-y (/ height 2.0)
                  radius (- (min center-x center-y) 50)
                  azimuth @current-azimuth
                  needle-length (* radius 0.7)]
              
              (when (> radius 0) ; Only draw if we have valid dimensions
                (let [;; Calculate needle end point (pointing north, adjusted for azimuth)
                      needle-angle (Math/toRadians (- azimuth))
                      needle-end-x (+ center-x (* needle-length (Math/sin needle-angle)))
                      needle-end-y (- center-y (* needle-length (Math/cos needle-angle)))]
                  
                  ;; Draw outer circle
                  (.drawCircle canvas center-x center-y radius paint-circle)
                  
                  ;; Draw cardinal directions
                  (.drawText canvas "N" center-x (- center-y radius -30) paint-north)
                  (.drawText canvas "S" center-x (+ center-y radius -10) paint-text)
                  (.drawText canvas "E" (+ center-x radius -20) (+ center-y 10) paint-text)
                  (.drawText canvas "W" (- center-x radius -20) (+ center-y 10) paint-text)
                  
                  ;; Draw center dot
                  (.drawCircle canvas center-x center-y 8.0 paint-needle)
                  
                  ;; Draw needle pointing north
                  (.drawLine canvas center-x center-y needle-end-x needle-end-y paint-needle)
                  
                  ;; Draw needle tip (triangle)
                  (let [tip-size 20.0
                        tip-angle1 (+ needle-angle (/ Math/PI 6))
                        tip-angle2 (- needle-angle (/ Math/PI 6))
                        tip1-x (+ needle-end-x (* tip-size (Math/sin (+ tip-angle1 Math/PI))))
                        tip1-y (- needle-end-y (* tip-size (Math/cos (+ tip-angle1 Math/PI))))
                        tip2-x (+ needle-end-x (* tip-size (Math/sin (+ tip-angle2 Math/PI))))
                        tip2-y (- needle-end-y (* tip-size (Math/cos (+ tip-angle2 Math/PI))))
                        path (Path.)]
                    (.moveTo path needle-end-x needle-end-y)
                    (.lineTo path tip1-x tip1-y)
                    (.lineTo path tip2-x tip2-y)
                    (.close path)
                    (.drawPath canvas path paint-needle))
                  
                  ;; Display current heading
                  (.drawText canvas (str "Heading: " (int azimuth) "°") 
                            center-x (+ center-y radius 60) paint-text))))))))
    
    ;; --- Sensor Event Listener ---
    
    (defn handle-sensor-changed [event]
      (let [sensor (.sensor event)
            sensor-type (.getType sensor)
            values (.values event)]
        (cond
          (= sensor-type Sensor/TYPE_ACCELEROMETER)
          (do
            (System/arraycopy values 0 @gravity-readings 0 3)
            (reset! has-gravity-data true))
    
          (= sensor-type Sensor/TYPE_MAGNETIC_FIELD)
          (do
            (System/arraycopy values 0 @geomagnetic-readings 0 3)
            (reset! has-geomagnetic-data true)))
    
        ;; Only calculate orientation if we have data from both sensors
        (when (and @has-gravity-data @has-geomagnetic-data)
          (let [success (SensorManager/getRotationMatrix @rotation-matrix nil @gravity-readings @geomagnetic-readings)]
            (when success
              (SensorManager/getOrientation @rotation-matrix @orientation-angles)
              ;; Azimuth is the first element (0) of the orientation angles
              (let [azimuth-rad (aget @orientation-angles 0)
                    azimuth-deg (Math/toDegrees azimuth-rad)
                    ;; Ensure azimuth is positive and between 0-359 degrees
                    display-azimuth (mod (+ (Math/round azimuth-deg) 360) 360)]
                (reset! current-azimuth display-azimuth)
                (when @compass-view
                  (.invalidate @compass-view))))))))
    
    (defn handle-accuracy-changed [sensor accuracy]
      (log-debug "Sensor accuracy changed for %s: %d" (.getName sensor) accuracy))
    
    ;; Create the SensorEventListener proxy
    (def sensor-event-listener
      (proxy [SensorEventListener] []
        (onSensorChanged [event] (handle-sensor-changed event))
        (onAccuracyChanged [sensor accuracy] (handle-accuracy-changed sensor accuracy))))
    
    ;; --- Android Lifecycle Observer ---
    
    (defn handle-lifecycle-event [source event]
      (log-debug "Lifecycle Event: %s" (.name event))
      (case (.name event)
        "ON_CREATE"
        (do
          (log-debug "ON_CREATE: Initializing UI and sensors.")
          ;; Initialize SensorManager
          (reset! sensor-manager (.getSystemService *context* Context/SENSOR_SERVICE))
          (when (nil? @sensor-manager)
            (log-debug "SensorManager not found!"))
    
          ;; Get specific sensors
          (reset! accelerometer (.getDefaultSensor @sensor-manager Sensor/TYPE_ACCELEROMETER))
          (reset! magnetometer (.getDefaultSensor @sensor-manager Sensor/TYPE_MAGNETIC_FIELD))
    
          (when (nil? @accelerometer)
            (log-debug "Accelerometer not available on this device."))
          (when (nil? @magnetometer)
            (log-debug "Magnetometer not available on this device."))
    
          ;; Create and add compass view
          (let [compass (create-compass-view *context*)]
            (.setMinimumWidth compass 400)
            (.setMinimumHeight compass 400)
            (reset! compass-view compass)
            (let [lp (android.widget.LinearLayout$LayoutParams.
                       400 ; fixed width
                       400)] ; fixed height
              (.setGravity lp Gravity/CENTER)
              (.addView *content-layout* compass lp))))
    
        "ON_START"
        (do
          (log-debug "ON_START: Registering sensor listeners.")
          (when (and @sensor-manager @accelerometer)
            (.registerListener @sensor-manager sensor-event-listener @accelerometer SensorManager/SENSOR_DELAY_UI))
          (when (and @sensor-manager @magnetometer)
            (.registerListener @sensor-manager sensor-event-listener @magnetometer SensorManager/SENSOR_DELAY_UI)))
    
        "ON_STOP"
        (do
          (log-debug "ON_STOP: Unregistering sensor listeners.")
          (when @sensor-manager
            (.unregisterListener @sensor-manager sensor-event-listener)))
    
        "ON_DESTROY"
        (log-debug "ON_DESTROY: Cleaning up resources.")
    
        ;; Default case for other events
        (log-debug "Unhandled Lifecycle Event: %s" (.name event))))
    
    ;; Create the LifecycleEventObserver proxy
    (def lifecycle-observer
      (proxy [LifecycleEventObserver] []
        (onStateChanged [source event] (handle-lifecycle-event source event))))
    
    ;; --- Main Entry Point ---
    
    (defn -main []
      (log-debug "-main function entered.")
    
      ;; Configure the main content layout
      (.setOrientation *content-layout* LinearLayout/VERTICAL)
      (.setGravity *content-layout* Gravity/CENTER)
      (.setBackgroundColor *content-layout* Color/WHITE)
    
      ;; Get the Activity's Lifecycle and add the observer
      (try
        (let [lifecycle (.. *context* (getLifecycle))]
          (log-debug "Current Lifecycle State: %s" (.getCurrentState lifecycle))
          (.addObserver lifecycle lifecycle-observer)
          (log-debug "Lifecycle observer registered successfully."))
        (catch Exception e
          (Log/e LOG_TAG "Error registering lifecycle observer" e))))

