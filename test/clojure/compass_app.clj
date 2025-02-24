(let [activity *context*
      content *content-layout*
      handler (android.os.Handler. (android.os.Looper/getMainLooper))
      tag "CompassApp"

      ;; Shader source code
      vertex-shader "uniform mat4 uProjectionMatrix;
                    uniform mat4 uRotationMatrix;
                    attribute vec4 vPosition;
                    attribute vec4 vColor;
                    varying vec4 fColor;
                    void main() {
                      gl_Position = uProjectionMatrix * uRotationMatrix * vPosition;
                      fColor = vColor;
                    }"
      
      fragment-shader "precision mediump float;
                      varying vec4 fColor;
                      void main() {
                        gl_FragColor = fColor;
                      }"

      ;; Create compass disk vertices (center + 32 points around circle)
      num-points 32
      radius 0.8
      disk-vertices (float-array 
                     (concat 
                       [0.0 0.0 0.0]  ; Center point
                       (flatten
                         (for [i (range (inc num-points))]
                           (let [angle (* 2.0 Math/PI (/ i num-points))]
                             [(* radius (Math/cos angle))
                              (* radius (Math/sin angle))
                              0.0])))))

      ;; Create disk colors (all red)
      disk-colors (float-array
                   (flatten
                     (repeat (+ 2 num-points) [1.0 0.0 0.0 1.0])))

      ;; Create north pointer vertices
      pointer-height 0.4  ; Half the radius
      pointer-width 0.1   ; Narrow triangle
      pointer-vertices (float-array
                        [0.0 radius 0.0          ; Tip
                         (- pointer-width) (- radius pointer-height) 0.0  ; Left base
                         pointer-width (- radius pointer-height) 0.0])    ; Right base

      ;; Create pointer colors (white)
      pointer-colors (float-array
                      (flatten
                        (repeat 3 [1.0 1.0 1.0 1.0])))

      ;; Create vertex and color buffers
      disk-vertex-buffer (let [vbb (java.nio.ByteBuffer/allocateDirect (* (count disk-vertices) 4))
                              native-order (java.nio.ByteOrder/nativeOrder)]
                          (.order vbb native-order)
                          (let [fb (.asFloatBuffer vbb)]
                            (.put fb disk-vertices)
                            (.position fb 0)
                            fb))

      disk-color-buffer (let [vbb (java.nio.ByteBuffer/allocateDirect (* (count disk-colors) 4))
                             native-order (java.nio.ByteOrder/nativeOrder)]
                         (.order vbb native-order)
                         (let [fb (.asFloatBuffer vbb)]
                           (.put fb disk-colors)
                           (.position fb 0)
                           fb))

      pointer-vertex-buffer (let [vbb (java.nio.ByteBuffer/allocateDirect (* (count pointer-vertices) 4))
                                 native-order (java.nio.ByteOrder/nativeOrder)]
                             (.order vbb native-order)
                             (let [fb (.asFloatBuffer vbb)]
                               (.put fb pointer-vertices)
                               (.position fb 0)
                               fb))

      pointer-color-buffer (let [vbb (java.nio.ByteBuffer/allocateDirect (* (count pointer-colors) 4))
                                native-order (java.nio.ByteOrder/nativeOrder)]
                            (.order vbb native-order)
                            (let [fb (.asFloatBuffer vbb)]
                              (.put fb pointer-colors)
                              (.position fb 0)
                              fb))

      ;; Store handles
      program-handle (atom 0)
      position-handle (atom 0)
      color-handle (atom 0)
      projection-matrix-handle (atom 0)
      rotation-matrix-handle (atom 0)
      projection-matrix (float-array 16)
      model-matrix (float-array 16)        ; For pointer rotation
      identity-matrix (float-array 16)     ; For static disk
      
      ;; Initialize identity matrix
      _ (android.opengl.Matrix/setIdentityM identity-matrix 0)

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

      ;; Helper function to create orthographic projection
      create-ortho-matrix (fn [width height]
                           (let [aspect (/ width height)
                                scale (if (> width height) [1.0 aspect 1.0] [aspect 1.0 1.0])]
                             (android.opengl.Matrix/orthoM projection-matrix 0
                                                          (- (first scale)) (first scale)
                                                          (- (second scale)) (second scale)
                                                          -1.0 1.0)))

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
            (android.util.Log/d tag (str "Shader program created: " @program-handle))
            
            ;; Get handles to shader variables
            (reset! position-handle 
              (android.opengl.GLES20/glGetAttribLocation @program-handle "vPosition"))
            (reset! color-handle
              (android.opengl.GLES20/glGetAttribLocation @program-handle "vColor"))
            (reset! projection-matrix-handle 
              (android.opengl.GLES20/glGetUniformLocation @program-handle "uProjectionMatrix"))
            (reset! rotation-matrix-handle
              (android.opengl.GLES20/glGetUniformLocation @program-handle "uRotationMatrix"))
            
            (android.util.Log/d tag (str "Shader handles - position: " @position-handle 
                                       " color: " @color-handle
                                       " projection: " @projection-matrix-handle
                                       " rotation: " @rotation-matrix-handle))))
        
        (onSurfaceChanged [gl width height]
          (android.util.Log/d tag (str "Surface changed: " width "x" height))
          (android.opengl.GLES20/glViewport 0 0 width height)
          (create-ortho-matrix width height))
        
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
          
          ;; Draw the compass disk (no rotation)
          (android.opengl.GLES20/glUniformMatrix4fv @rotation-matrix-handle
                                                   1 false identity-matrix 0)
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
            0 (+ 2 num-points))
          
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
          (android.opengl.GLES20/glDisableVertexAttribArray @color-handle)))

      gl-view (doto (proxy [android.opengl.GLSurfaceView] [activity]
                     (init []
                       (android.util.Log/d tag "Initializing GL view")))
               (.setEGLContextClientVersion 2)
               (.setRenderer compass-renderer)
               (.setRenderMode android.opengl.GLSurfaceView/RENDERMODE_WHEN_DIRTY))

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
              
              ;; Update rotation matrix for compass pointer
              (android.opengl.Matrix/setRotateM model-matrix 0 
                                               (-> (aget orientation 0) 
                                                   Math/toDegrees 
                                                   (+ 90.0))  ; Adjust for screen orientation
                                               0.0 0.0 1.0)
              
              ;; Request a redraw
              (.requestRender gl-view)
              
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