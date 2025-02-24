(let [activity *context*
      content *content-layout*
      handler (android.os.Handler. (android.os.Looper/getMainLooper))
      tag "CompassApp"

      ;; Create sensor manager and get sensors
      _ (android.util.Log/d tag "Getting sensor service")
      sensor-manager (.getSystemService activity android.content.Context/SENSOR_SERVICE)
      accelerometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_ACCELEROMETER)
      magnetometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_MAGNETIC_FIELD)
      
      _ (android.util.Log/d tag (str "Accelerometer: " accelerometer))
      _ (android.util.Log/d tag (str "Magnetometer: " magnetometer))

      ;; Create OpenGL surface view
      compass-renderer (proxy [android.opengl.GLSurfaceView$Renderer] []
        (onSurfaceCreated [gl config]
          (android.util.Log/d tag "Surface created")
          (android.opengl.GLES20/glClearColor 0.0 0.0 0.0 1.0))
        
        (onSurfaceChanged [gl width height]
          (android.util.Log/d tag (str "Surface changed: " width "x" height))
          (android.opengl.GLES20/glViewport 0 0 width height))
        
        (onDrawFrame [gl]
          (android.opengl.GLES20/glClear android.opengl.GLES20/GL_COLOR_BUFFER_BIT)))

      gl-view (doto (proxy [android.opengl.GLSurfaceView] [activity]
                     (init []
                       (android.util.Log/d tag "Initializing GL view")))
               (.setEGLContextClientVersion 2)
               (.setRenderer compass-renderer)
               (.setRenderMode android.opengl.GLSurfaceView/RENDERMODE_CONTINUOUSLY))

      ;; Arrays for sensor data
      accel-data (float-array 3)
      magnetic-data (float-array 3)
      rotation-matrix (float-array 16)
      orientation (float-array 3)

      ;; Track last logged orientation for filtering
      last-orientation (atom [0 0 0])
      min-change 0.1  ; About 5.7 degrees

      ;; Sensor event listener
      sensor-listener (proxy [android.hardware.SensorEventListener] []
        (onSensorChanged [event]
          (let [sensor-type (.getType (.sensor event))]
            (cond
              (= sensor-type android.hardware.Sensor/TYPE_ACCELEROMETER)
              (System/arraycopy (.values event) 0 accel-data 0 3)
              
              (= sensor-type android.hardware.Sensor/TYPE_MAGNETIC_FIELD)
              (System/arraycopy (.values event) 0 magnetic-data 0 3))
            
            (when (android.hardware.SensorManager/getRotationMatrix 
                   rotation-matrix nil accel-data magnetic-data)
              (android.hardware.SensorManager/getOrientation 
               rotation-matrix orientation)
              
              ;; Only log if orientation changed significantly
              (let [current (vec orientation)
                    last @last-orientation
                    changes (map #(Math/abs (- %1 %2)) current last)
                    changed? (some #(> % min-change) changes)]
                (when changed?
                  (reset! last-orientation current)
                  (android.util.Log/d tag (str "Orientation: " current)))))))
        
        (onAccuracyChanged [sensor accuracy]
          (android.util.Log/d tag (str "Sensor accuracy changed: " accuracy))))]

  ;; Register sensor listeners
  (android.util.Log/d tag "Registering sensor listeners")
  (.registerListener sensor-manager 
                    sensor-listener
                    accelerometer
                    android.hardware.SensorManager/SENSOR_DELAY_GAME)
  (.registerListener sensor-manager
                    sensor-listener
                    magnetometer
                    android.hardware.SensorManager/SENSOR_DELAY_GAME)

  ;; Set up layout
  (android.util.Log/d tag "Setting up layout")
  (.setLayoutParams gl-view (android.widget.LinearLayout$LayoutParams.
                            android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                            android.widget.LinearLayout$LayoutParams/MATCH_PARENT))
  (.removeAllViews content)  ; Clear any existing views
  (.addView content gl-view)

  "Compass initialized") 