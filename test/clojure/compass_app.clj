(let [activity *context*
      content *content-layout*
      handler (android.os.Handler. (android.os.Looper/getMainLooper))
      tag "CompassApp"

      ;; Shader source code
      vertex-shader "attribute vec4 vPosition;
                    void main() {
                      gl_Position = vPosition;
                    }"
      
      fragment-shader "precision mediump float;
                      void main() {
                        gl_FragColor = vec4(1.0, 0.0, 0.0, 1.0);
                      }"

      ;; Helper function to compile shader
      compile-shader (fn [type source]
        (let [shader (android.opengl.GLES20/glCreateShader type)]
          (android.opengl.GLES20/glShaderSource shader source)
          (android.opengl.GLES20/glCompileShader shader)
          (let [compiled (java.nio.IntBuffer/allocate 1)]
            (android.opengl.GLES20/glGetShaderiv shader android.opengl.GLES20/GL_COMPILE_STATUS compiled)
            (when (zero? (.get compiled 0))
              (let [error (android.opengl.GLES20/glGetShaderInfoLog shader)]
                (android.util.Log/e tag (str "Shader compilation failed: " error))
                (android.opengl.GLES20/glDeleteShader shader)
                (throw (RuntimeException. (str "Shader compilation failed: " error))))))
          shader))

      ;; Store program handle
      program-handle (atom 0)

      ;; Arrays for sensor data
      accel-data (float-array 3)
      magnetic-data (float-array 3)
      rotation-matrix (float-array 16)
      orientation (float-array 3)
      last-orientation (atom [0 0 0])
      min-change 0.1  ; About 5.7 degrees

      ;; Create sensor manager and get sensors
      _ (android.util.Log/d tag "Getting sensor service")
      sensor-manager (.getSystemService activity android.content.Context/SENSOR_SERVICE)
      accelerometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_ACCELEROMETER)
      magnetometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_MAGNETIC_FIELD)
      
      _ (android.util.Log/d tag (str "Accelerometer: " accelerometer))
      _ (android.util.Log/d tag (str "Magnetometer: " magnetometer))

      ;; Create sensor event listener
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
          (android.util.Log/d tag (str "Sensor accuracy changed: " accuracy))))

      ;; Create OpenGL surface view with updated renderer
      compass-renderer (proxy [android.opengl.GLSurfaceView$Renderer] []
        (onSurfaceCreated [gl config]
          (android.util.Log/d tag "Surface created")
          (android.opengl.GLES20/glClearColor 0.0 0.0 0.0 1.0)
          
          ;; Create and link shader program
          (let [vertex-shader-handle (compile-shader android.opengl.GLES20/GL_VERTEX_SHADER vertex-shader)
                fragment-shader-handle (compile-shader android.opengl.GLES20/GL_FRAGMENT_SHADER fragment-shader)
                program (android.opengl.GLES20/glCreateProgram)]
            
            (android.opengl.GLES20/glAttachShader program vertex-shader-handle)
            (android.opengl.GLES20/glAttachShader program fragment-shader-handle)
            (android.opengl.GLES20/glLinkProgram program)
            
            (let [linked (java.nio.IntBuffer/allocate 1)]
              (android.opengl.GLES20/glGetProgramiv program android.opengl.GLES20/GL_LINK_STATUS linked)
              (when (zero? (.get linked 0))
                (let [error (android.opengl.GLES20/glGetProgramInfoLog program)]
                  (android.util.Log/e tag (str "Program linking failed: " error))
                  (android.opengl.GLES20/glDeleteProgram program)
                  (throw (RuntimeException. (str "Program linking failed: " error))))))
            
            (reset! program-handle program)
            (android.util.Log/d tag (str "Shader program created: " @program-handle))))
        
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
               (.setRenderMode android.opengl.GLSurfaceView/RENDERMODE_CONTINUOUSLY))]

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