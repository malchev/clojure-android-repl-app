;; Compass
(def TAG "CompassApp")
(def VERTEX-SHADER "uniform mat4 uProjectionMatrix;
                    uniform mat4 uRotationMatrix;
                    attribute vec4 vPosition;
                    attribute vec4 vColor;
                    varying vec4 fColor;
                    void main() {
                      gl_Position = uProjectionMatrix * uRotationMatrix * vPosition;
                      fColor = vColor;
                    }")

(def FRAGMENT-SHADER "precision mediump float;
                      varying vec4 fColor;
                      void main() {
                        gl_FragColor = fColor;
                      }")

;; State
(def active? (atom true))
(def program-handle (atom 0))
(def position-handle (atom 0))
(def color-handle (atom 0))
(def projection-matrix-handle (atom 0))
(def rotation-matrix-handle (atom 0))
(def last-orientation (atom [0 0 0]))
(def last-update-time (atom 0))

;; Forward declarations for circular references
(declare cleanup)
(declare gl-view)
(declare sensor-listener)
(declare sensor-manager)
(declare accelerometer)
(declare magnetometer)

;; Geometry setup functions
(defn create-disk-vertices [num-points radius]
  (float-array 
    (concat 
      [0.0 0.0 0.0]  ; Center point
      (flatten
        (for [i (range (inc num-points))]
          (let [angle (* 2.0 Math/PI (/ i num-points))]
            [(* radius (Math/cos angle))
             (* radius (Math/sin angle))
             0.0]))))))

(defn create-disk-colors [num-points]
  (float-array
    (flatten
      (repeat (+ 2 num-points) [1.0 0.0 0.0 1.0]))))

(defn create-pointer-vertices [radius pointer-height pointer-width]
  (float-array
    [0.0 radius 0.0          ; Tip
     (- pointer-width) (- radius pointer-height) 0.0  ; Left base
     pointer-width (- radius pointer-height) 0.0]))   ; Right base

(defn create-pointer-colors []
  (float-array
    (flatten
      (repeat 3 [1.0 1.0 1.0 1.0]))))

(defn create-buffer [data]
  (let [vbb (java.nio.ByteBuffer/allocateDirect (* (count data) 4))
        native-order (java.nio.ByteOrder/nativeOrder)]
    (.order vbb native-order)
    (let [fb (.asFloatBuffer vbb)]
      (.put fb data)
      (.position fb 0)
      fb)))

;; OpenGL utility functions
(defn compile-shader [type source]
  (let [shader (android.opengl.GLES20/glCreateShader type)]
    (android.opengl.GLES20/glShaderSource shader source)
    (android.opengl.GLES20/glCompileShader shader)
    (let [compiled (java.nio.IntBuffer/allocate 1)]
      (android.opengl.GLES20/glGetShaderiv shader android.opengl.GLES20/GL_COMPILE_STATUS compiled)
      (when (zero? (.get compiled 0))
        (let [error (android.opengl.GLES20/glGetShaderInfoLog shader)]
          (android.util.Log/e TAG (str "Shader compilation failed: " error))
          (android.opengl.GLES20/glDeleteShader shader)
          (throw (RuntimeException. (str "Shader compilation failed: " error))))))
    shader))

(defn create-ortho-matrix [projection-matrix width height]
  (let [aspect (/ width height)
        scale (if (> width height) [1.0 aspect 1.0] [aspect 1.0 1.0])]
    (android.opengl.Matrix/orthoM projection-matrix 0
                                  (- (first scale)) (first scale)
                                  (- (second scale)) (second scale)
                                  -1.0 1.0)))

(defn lerp-angle [start end factor]
  (let [diff (- end start)]
    (+ start (* diff factor))))

;; Cleanup function
(defn cleanup []
  (android.util.Log/d TAG "Cleaning up compass resources")
  (reset! active? false)
  ;; Unregister sensor listeners
  (try
    (.unregisterListener sensor-manager sensor-listener)
    (catch Exception e
      (android.util.Log/e TAG (str "Error unregistering sensors: " (.getMessage e)))))
  ;; Pause OpenGL rendering if view is still valid
  (try
    (.onPause gl-view)
    (catch Exception e
      (android.util.Log/e TAG (str "Error pausing GL view: " (.getMessage e))))))

;; Create compass app
(defn initialize-compass [activity content]
  (android.util.Log/d TAG "Initializing compass app")

  ;; Constants
  (def NUM-POINTS 32)
  (def RADIUS 0.5)
  (def POINTER-HEIGHT 0.25)
  (def POINTER-WIDTH 0.06)
  (def UPDATE-INTERVAL 50)
  (def SMOOTHING-FACTOR 0.3)
  (def MIN-CHANGE 0.1)

  ;; Reset active state
  (reset! active? true)

  ;; Create geometry
  (def disk-vertices (create-disk-vertices NUM-POINTS RADIUS))
  (def disk-colors (create-disk-colors NUM-POINTS))
  (def pointer-vertices (create-pointer-vertices RADIUS POINTER-HEIGHT POINTER-WIDTH))
  (def pointer-colors (create-pointer-colors))

  ;; Create buffers
  (def disk-vertex-buffer (create-buffer disk-vertices))
  (def disk-color-buffer (create-buffer disk-colors))
  (def pointer-vertex-buffer (create-buffer pointer-vertices))
  (def pointer-color-buffer (create-buffer pointer-colors))

  ;; Initialize matrices
  (def projection-matrix (float-array 16))
  (def model-matrix (float-array 16))
  (def disk-matrix (float-array 16))
  (def temp-matrix (float-array 16))
  (android.opengl.Matrix/setIdentityM model-matrix 0)
  (android.opengl.Matrix/setIdentityM disk-matrix 0)

  ;; Sensor data arrays
  (def accel-data (float-array 3))
  (def magnetic-data (float-array 3))
  (def rotation-matrix (float-array 16))
  (def orientation (float-array 3))

  ;; Set up sensors
  (android.util.Log/d TAG "Getting sensor service")
  (def sensor-manager (.getSystemService activity android.content.Context/SENSOR_SERVICE))
  (def accelerometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_ACCELEROMETER))
  (def magnetometer (.getDefaultSensor sensor-manager android.hardware.Sensor/TYPE_MAGNETIC_FIELD))
  (android.util.Log/d TAG (str "Accelerometer: " accelerometer))
  (android.util.Log/d TAG (str "Magnetometer: " magnetometer))

  ;; Create OpenGL renderer
  (def compass-renderer 
    (proxy [android.opengl.GLSurfaceView$Renderer] []
      (onSurfaceCreated [gl config]
        (android.util.Log/d TAG "Surface created")
        (android.opengl.GLES20/glClearColor 0.0 0.0 0.0 1.0)

        ;; Create and link shader program
        (let [vertex-shader-handle (compile-shader android.opengl.GLES20/GL_VERTEX_SHADER VERTEX-SHADER)
              fragment-shader-handle (compile-shader android.opengl.GLES20/GL_FRAGMENT_SHADER FRAGMENT-SHADER)
              program (android.opengl.GLES20/glCreateProgram)]

          (android.opengl.GLES20/glAttachShader program vertex-shader-handle)
          (android.opengl.GLES20/glAttachShader program fragment-shader-handle)
          (android.opengl.GLES20/glLinkProgram program)

          (let [linked (java.nio.IntBuffer/allocate 1)]
            (android.opengl.GLES20/glGetProgramiv program android.opengl.GLES20/GL_LINK_STATUS linked)
            (when (zero? (.get linked 0))
              (let [error (android.opengl.GLES20/glGetProgramInfoLog program)]
                (android.util.Log/e TAG (str "Program linking failed: " error))
                (android.opengl.GLES20/glDeleteProgram program)
                (throw (RuntimeException. (str "Program linking failed: " error))))))

          (reset! program-handle program)
          (android.util.Log/d TAG (str "Shader program created: " @program-handle))

          ;; Get handles to shader variables
          (reset! position-handle 
            (android.opengl.GLES20/glGetAttribLocation @program-handle "vPosition"))
          (reset! color-handle
            (android.opengl.GLES20/glGetAttribLocation @program-handle "vColor"))
          (reset! projection-matrix-handle 
            (android.opengl.GLES20/glGetUniformLocation @program-handle "uProjectionMatrix"))
          (reset! rotation-matrix-handle
            (android.opengl.GLES20/glGetUniformLocation @program-handle "uRotationMatrix"))

          (android.util.Log/d TAG (str "Shader handles - position: " @position-handle 
                                     " color: " @color-handle
                                     " projection: " @projection-matrix-handle
                                     " rotation: " @rotation-matrix-handle))))

      (onSurfaceChanged [gl width height]
        (android.util.Log/d TAG (str "Surface changed: " width "x" height))
        (android.opengl.GLES20/glViewport 0 0 width height)
        (create-ortho-matrix projection-matrix width height))

      (onDrawFrame [gl]
        (android.opengl.GLES20/glClear android.opengl.GLES20/GL_COLOR_BUFFER_BIT)

        ;; Use the shader program
        (android.opengl.GLES20/glUseProgram @program-handle)

        ;; Set the projection matrix
        (android.opengl.GLES20/glUniformMatrix4fv @projection-matrix-handle 
                                               1 false projection-matrix 0)

        ;; Enable vertex arrays
        (android.opengl.GLES20/glEnableVertexAttribArray @position-handle)
        (android.opengl.GLES20/glEnableVertexAttribArray @color-handle)

        ;; Draw the compass disk (with full 3D rotation)
        (android.opengl.GLES20/glUniformMatrix4fv @rotation-matrix-handle
                                               1 false disk-matrix 0)
        (android.opengl.GLES20/glVertexAttribPointer 
          @position-handle 3 
          android.opengl.GLES20/GL_FLOAT false 
          0 disk-vertex-buffer)
        (android.opengl.GLES20/glVertexAttribPointer
          @color-handle 4
          android.opengl.GLES20/GL_FLOAT false
          0 disk-color-buffer)
        (android.opengl.GLES20/glDrawArrays 
          android.opengl.GLES20/GL_TRIANGLE_FAN 
          0 (+ 2 NUM-POINTS))

        ;; Draw the north pointer (with rotation from sensors)
        (android.opengl.GLES20/glUniformMatrix4fv @rotation-matrix-handle
                                               1 false model-matrix 0)
        (android.opengl.GLES20/glVertexAttribPointer 
          @position-handle 3 
          android.opengl.GLES20/GL_FLOAT false 
          0 pointer-vertex-buffer)
        (android.opengl.GLES20/glVertexAttribPointer
          @color-handle 4
          android.opengl.GLES20/GL_FLOAT false
          0 pointer-color-buffer)
        (android.opengl.GLES20/glDrawArrays 
          android.opengl.GLES20/GL_TRIANGLES
          0 3)

        ;; Disable vertex arrays
        (android.opengl.GLES20/glDisableVertexAttribArray @position-handle)
        (android.opengl.GLES20/glDisableVertexAttribArray @color-handle))))

  ;; Create GL Surface View
  (def gl-view 
    (doto (proxy [android.opengl.GLSurfaceView] [activity]
            (init []
              (android.util.Log/d TAG "Initializing GL view"))

            ;; Override onDetachedFromWindow to properly handle cleanup
            (onDetachedFromWindow []
              (android.util.Log/d TAG "GL view detached from window")
              (proxy-super onDetachedFromWindow)
              (cleanup)))
      (.setEGLContextClientVersion 2)
      (.setRenderer compass-renderer)
      (.setRenderMode android.opengl.GLSurfaceView/RENDERMODE_WHEN_DIRTY)))

  ;; Create sensor event listener
  (def sensor-listener 
    (proxy [android.hardware.SensorEventListener] []
      (onSensorChanged [event]
        (when @active? ; Only process events when activity is active
          (let [sensor-type (.getType (.sensor event))]
            (cond
              (= sensor-type android.hardware.Sensor/TYPE_ACCELEROMETER)
              (System/arraycopy (.values event) 0 accel-data 0 3)

              (= sensor-type android.hardware.Sensor/TYPE_MAGNETIC_FIELD)
              (System/arraycopy (.values event) 0 magnetic-data 0 3))

            (when (android.hardware.SensorManager/getRotationMatrix 
                   rotation-matrix nil accel-data magnetic-data)
              ;; Check if enough time has passed since last update
              (let [current-time (System/currentTimeMillis)]
                (when (> (- current-time @last-update-time) UPDATE-INTERVAL)
                  (reset! last-update-time current-time)

                  ;; Remap coordinates to match our desired orientation
                  (let [remapped-matrix (float-array 16)]
                    ;; Remap so that when phone is flat, disk is flat (Y is up)
                    (android.hardware.SensorManager/remapCoordinateSystem
                      rotation-matrix
                      android.hardware.SensorManager/AXIS_X
                      android.hardware.SensorManager/AXIS_Z
                      remapped-matrix)

                    (android.hardware.SensorManager/getOrientation 
                     remapped-matrix orientation)

                    ;; Smooth the orientation changes
                    (let [current (vec orientation)
                          last @last-orientation
                          smoothed (mapv #(lerp-angle %1 %2 SMOOTHING-FACTOR) last current)
                          changes (map #(Math/abs (- %1 %2)) current last)
                          changed? (some #(> % MIN-CHANGE) changes)]

                      ;; Update pointer rotation (just azimuth around Z)
                      (android.opengl.Matrix/setRotateM model-matrix 0 
                                                       (-> (first smoothed)
                                                           Math/toDegrees 
                                                           (+ 90.0))  ; Adjust for screen orientation
                                                       0.0 0.0 1.0)

                      ;; Copy the remapped rotation matrix for the disk
                      (System/arraycopy remapped-matrix 0 disk-matrix 0 16)

                      ;; Adjust for screen orientation (rotate 90 degrees around X)
                      (android.opengl.Matrix/setRotateM temp-matrix 0 90.0 1.0 0.0 0.0)
                      (android.opengl.Matrix/multiplyMM disk-matrix 0 temp-matrix 0 disk-matrix 0)

                      ;; Request a redraw
                      (.requestRender gl-view)

                      ;; Only log if orientation changed significantly
                      (when changed?
                        (reset! last-orientation smoothed)
                        (android.util.Log/d TAG (str "Orientation: " smoothed)))))))))))

      (onAccuracyChanged [sensor accuracy]
        (android.util.Log/d TAG (str "Sensor accuracy changed: " accuracy)))))

  ;; Register sensor listeners
  (android.util.Log/d TAG "Registering sensor listeners")
  (.registerListener sensor-manager 
                   sensor-listener
                   accelerometer
                   android.hardware.SensorManager/SENSOR_DELAY_GAME)
  (.registerListener sensor-manager
                   sensor-listener
                   magnetometer
                   android.hardware.SensorManager/SENSOR_DELAY_GAME)

  ;; Set up layout
  (android.util.Log/d TAG "Setting up layout")
  (.setLayoutParams gl-view (android.widget.LinearLayout$LayoutParams.
                           android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                           android.widget.LinearLayout$LayoutParams/MATCH_PARENT))
  (.removeAllViews content)  ; Clear any existing views
  (.addView content gl-view)

  ;; Add a view lifecycle listener to handle lifecycle events
  (.addOnAttachStateChangeListener content
    (proxy [android.view.View$OnAttachStateChangeListener] []
      (onViewAttachedToWindow [v] 
        (android.util.Log/d TAG "View attached to window"))
      (onViewDetachedFromWindow [v]
        (android.util.Log/d TAG "View detached from window")
        (cleanup))))

  ;; Add lifecycle hooks directly to activity if possible
  (try
    ;; Try to access isDestroyed field
    (let [field (.getDeclaredField (.getClass activity) "isDestroyed")]
      ;; If we get here, the field exists, so try to add lifecycle hooks
      (.addOnAttachStateChangeListener activity
        (proxy [android.view.View$OnAttachStateChangeListener] []
          (onViewAttachedToWindow [v] nil)
          (onViewDetachedFromWindow [v]
            (android.util.Log/d TAG "Activity view detached - cleaning up")
            (cleanup)))))
    (catch Exception e
      (android.util.Log/d TAG "Could not set up direct activity hooks, using view listener only")))

  "Compass initialized")

;; Entry point
(defn -main []
  (initialize-compass *context* *content-layout*))
