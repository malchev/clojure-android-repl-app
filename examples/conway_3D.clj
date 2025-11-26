; Conway's Game of Life on a rotating 3D cube with front-face zoom

(import
  [android.widget LinearLayout Button]
  [android.view View View$OnClickListener View$OnTouchListener ViewGroup$LayoutParams MotionEvent WindowManager]
  [android.graphics Canvas Paint Color Bitmap Bitmap$Config Rect]
  [android.os Handler Looper]
  [android.util Log]
  [android.content Context]
  [android.opengl GLES20 GLSurfaceView GLSurfaceView$Renderer Matrix GLUtils]
  [javax.microedition.khronos.egl EGLConfig]
  [javax.microedition.khronos.opengles GL10]
  [java.nio ByteBuffer ByteOrder FloatBuffer ShortBuffer]
  [androidx.lifecycle LifecycleEventObserver Lifecycle$Event])

(def TAG "ClojureApp")

(defn debug-log [msg]
  (Log/d TAG (str msg)))

;;; --- Game and Animation State ---
(def grid-size 20)
(def game-state (atom (vec (repeat grid-size (vec (repeat grid-size false))))))
(def is-playing (atom false))
(def auto-random-mode? (atom false))
(def handler (atom nil))
(def game-loop-runnable (atom nil))
(def texture-dirty? (atom true))

(def animation-state (atom :rotating)) ; States: :rotating, :zooming-in, :zoomed-in, :zooming-out
(def animation-progress (atom 0.0))
(def animation-duration 500.0) ; in milliseconds
(def last-frame-time (atom 0))

(def rotation-x (atom 20.0))
(def rotation-y (atom 0.0))
(def saved-rotation-x (atom 20.0))
(def saved-rotation-y (atom 0.0))
(def target-rotation-y (atom 0.0))
(def camera-zoom (atom {:rotating -5.0 :zoomed-in -4.0}))
(def margin-factor 1.05)

;;; --- Game Logic ---
(defn get-cell [grid x y]
  (if (and (>= x 0) (< x grid-size) (>= y 0) (< y grid-size))
    (get-in grid [y x])
    false))

(defn count-neighbors [grid x y]
  (let [offsets [[-1 -1] [-1 0] [-1 1] [0 -1] [0 1] [1 -1] [1 0] [1 1]]]
    (reduce (fn [count [dx dy]]
              (if (get-cell grid (+ x dx) (+ y dy))
                (inc count)
                count))
            0
            offsets)))

(defn next-cell-state [current-state neighbor-count]
  (if current-state
    (or (= neighbor-count 2) (= neighbor-count 3))
    (= neighbor-count 3)))

(defn calculate-next-grid [current-grid]
  (vec (for [y (range grid-size)]
         (vec (for [x (range grid-size)]
                (let [current (get-cell current-grid x y)
                      neighbors (count-neighbors current-grid x y)]
                  (next-cell-state current neighbors)))))))

(defn randomize-grid []
  (debug-log "Randomizing board")
  (let [new-grid (vec (for [_ (range grid-size)]
                        (vec (for [_ (range grid-size)]
                               (> (rand) 0.65)))))]
    (reset! game-state new-grid)
    (reset! texture-dirty? true)))

(defn step-game []
  (let [current-grid @game-state
        new-grid (calculate-next-grid current-grid)]
    (if (= current-grid new-grid)
      (if @auto-random-mode?
        (do
          (debug-log "Stable state in auto-random mode. Resetting board.")
          (randomize-grid))
        (do
          (debug-log "Stable state reached. Stopping play.")
          (reset! is-playing false)))
      (do
        (reset! game-state new-grid)
        (reset! texture-dirty? true)))))

(defn toggle-cell [x y]
  (swap! game-state update-in [y x] not)
  (reset! texture-dirty? true)
  (debug-log (str "Toggled cell at (" x ", " y ")")))

(defn clear-grid []
  (debug-log "Clear button pressed")
  (reset! game-state (vec (repeat grid-size (vec (repeat grid-size false)))))
  (reset! texture-dirty? true))

;;; --- OpenGL Helpers and Shaders ---
(def vertex-shader-code
  (str "uniform mat4 uMVPMatrix;\n"
       "attribute vec4 aPosition;\n"
       "attribute vec2 aTexCoord;\n"
       "varying vec2 vTexCoord;\n"
       "void main() {\n"
       "  gl_Position = uMVPMatrix * aPosition;\n"
       "  vTexCoord = aTexCoord;\n"
       "}\n"))

(def fragment-shader-code
  (str "precision mediump float;\n"
       "varying vec2 vTexCoord;\n"
       "uniform sampler2D sTexture;\n"
       "void main() {\n"
       "  gl_FragColor = texture2D(sTexture, vTexCoord);\n"
       "}\n"))

(defn lerp [a b t]
  (+ a (* (- b a) t)))

(defn load-shader [type code]
  (let [shader (GLES20/glCreateShader type)]
    (GLES20/glShaderSource shader code)
    (GLES20/glCompileShader shader)
    shader))

(defn create-float-buffer [data]
  (let [bb (ByteBuffer/allocateDirect (* (count data) 4))
        fb (.asFloatBuffer (.order bb (ByteOrder/nativeOrder)))]
    (.put fb (float-array data))
    (.position fb 0)
    fb))

(defn create-short-buffer [data]
  (let [bb (ByteBuffer/allocateDirect (* (count data) 2))
        sb (.asShortBuffer (.order bb (ByteOrder/nativeOrder)))]
    (.put sb (short-array data))
    (.position sb 0)
    sb))

;;; --- Texture Generation ---
(defn create-grid-bitmap [state]
  (let [bitmap-size 256
        cell-draw-size (float (/ bitmap-size grid-size))
        bmp (Bitmap/createBitmap bitmap-size bitmap-size Bitmap$Config/ARGB_8888)
        canvas (Canvas. bmp)
        paint (Paint.)]
    (.setAntiAlias paint true)
    (.drawColor canvas Color/WHITE)
    (doseq [y (range grid-size) x (range grid-size)]
      (when (get-in state [y x])
        (let [neighbors (count-neighbors state x y)
              gray-value (max 0 (- 224 (* neighbors 28)))]
          (.setColor paint (Color/rgb gray-value gray-value gray-value))
          (.drawRect canvas (* x cell-draw-size) (* y cell-draw-size) (* (inc x) cell-draw-size) (* (inc y) cell-draw-size) paint))))
    (.setColor paint (Color/rgb 200 200 200))
    (.setStrokeWidth paint 1.0)
    (dotimes [i (inc grid-size)]
      (let [pos (* i cell-draw-size)]
        (.drawLine canvas 0 pos bitmap-size pos paint)
        (.drawLine canvas pos 0 pos bitmap-size paint)))
    bmp))

;;; --- Cube Geometry ---
(def cube-size 1.0)
(def cube-vertices
  (let [s cube-size]
    (create-float-buffer
     [; Front
      (- s) (- s) s,   s (- s) s,   s s s,   (- s) s s,
      ; Back
      s (- s) (- s), (- s) (- s) (- s), (- s) s (- s), s s (- s),
      ; Left
      (- s) (- s) (- s), (- s) (- s) s,   (- s) s s,   (- s) s (- s),
      ; Right
      s (- s) s,   s (- s) (- s),   s s (- s),   s s s,
      ; Top
      (- s) s s,   s s s,   s s (- s), (- s) s (- s),
      ; Bottom
      (- s) (- s) (- s), s (- s) (- s), s (- s) s,   (- s) (- s) s])))

(def texture-coords
  (create-float-buffer (apply concat (repeat 6 [0.0 1.0, 1.0 1.0, 1.0 0.0, 0.0 0.0]))))

(def draw-order
  (create-short-buffer (flatten (for [i (range 6) :let [start (* i 4)]] [start (inc start) (+ start 2) start (+ start 2) (+ start 3)]))))

;;; --- OpenGL Renderer ---
(defn create-renderer []
  (let [gl-program (atom 0), texture-id (atom 0), mvp-matrix-handle (atom 0), position-handle (atom 0), tex-coord-handle (atom 0)
        view-matrix (float-array 16), projection-matrix (float-array 16), mvp-matrix (float-array 16), model-matrix (float-array 16)]
    (proxy [GLSurfaceView$Renderer] []
      (onSurfaceCreated [gl config]
        (GLES20/glClearColor 0.9 0.9 0.9 1.0)
        (GLES20/glEnable GLES20/GL_DEPTH_TEST)
        (GLES20/glEnable GLES20/GL_CULL_FACE)
        (GLES20/glCullFace GLES20/GL_BACK)
        (let [vs (load-shader GLES20/GL_VERTEX_SHADER vertex-shader-code), fs (load-shader GLES20/GL_FRAGMENT_SHADER fragment-shader-code), prog (GLES20/glCreateProgram)]
          (GLES20/glAttachShader prog vs) (GLES20/glAttachShader prog fs) (GLES20/glLinkProgram prog) (reset! gl-program prog))
        (reset! position-handle (GLES20/glGetAttribLocation @gl-program "aPosition"))
        (reset! tex-coord-handle (GLES20/glGetAttribLocation @gl-program "aTexCoord"))
        (reset! mvp-matrix-handle (GLES20/glGetUniformLocation @gl-program "uMVPMatrix"))
        (let [textures (int-array 1)]
          (GLES20/glGenTextures 1 textures 0) (reset! texture-id (first textures))
          (GLES20/glBindTexture GLES20/GL_TEXTURE_2D @texture-id)
          (GLES20/glTexParameteri GLES20/GL_TEXTURE_2D GLES20/GL_TEXTURE_MIN_FILTER GLES20/GL_NEAREST)
          (GLES20/glTexParameteri GLES20/GL_TEXTURE_2D GLES20/GL_TEXTURE_MAG_FILTER GLES20/GL_LINEAR))
        (reset! last-frame-time (System/currentTimeMillis)))

      (onSurfaceChanged [gl width height]
        (debug-log (str "Surface changed: " width " x " height))
        (GLES20/glViewport 0 0 width height)
        (let [ratio (/ (float width) height)
              fov-y 45.0
              half-fov-rad (/ (* fov-y (/ Math/PI 180.0)) 2.0)
              tan-half-fov (Math/tan half-fov-rad)
              object-size (* cube-size 2.0 margin-factor)
              dist-to-fit-height (/ (/ object-size 2.0) tan-half-fov)
              dist-to-fit-width (/ (/ object-size 2.0) (* ratio tan-half-fov))
              final-dist (max dist-to-fit-height dist-to-fit-width)
              corrected-dist (+ final-dist cube-size)]
          (debug-log (str "Calculated zoom distance: " corrected-dist " (ratio: " ratio ")"))
          (Matrix/perspectiveM projection-matrix 0 fov-y ratio 0.1 100.0)
          (swap! camera-zoom assoc :zoomed-in corrected-dist :rotating (* corrected-dist 1.2))))

      (onDrawFrame [gl]
        (when @is-playing (step-game))
        (when @texture-dirty?
          (let [bmp (create-grid-bitmap @game-state)]
            (GLES20/glBindTexture GLES20/GL_TEXTURE_2D @texture-id)
            (GLUtils/texImage2D GLES20/GL_TEXTURE_2D 0 bmp 0)
            (.recycle bmp) (reset! texture-dirty? false)))

        (let [current-time (System/currentTimeMillis)
              delta-time (float (- current-time @last-frame-time))
              zooms @camera-zoom
              [current-rot-x current-rot-y current-zoom]
              (case @animation-state
                :rotating
                (do (swap! rotation-y #(+ % (* 0.04 delta-time))) [@rotation-x @rotation-y (:rotating zooms)])

                :zooming-in
                (let [p (min 1.0 (swap! animation-progress + (/ delta-time animation-duration)))]
                  (when (>= p 1.0) (reset! animation-state :zoomed-in))
                  [(lerp @saved-rotation-x 0.0 p)
                   (lerp @saved-rotation-y @target-rotation-y p)
                   (lerp (:rotating zooms) (:zoomed-in zooms) p)])

                :zoomed-in
                [0.0 0.0 (:zoomed-in zooms)]

                :zooming-out
                (let [p (min 1.0 (swap! animation-progress + (/ delta-time animation-duration)))]
                  (when (>= p 1.0)
                    (reset! rotation-x @saved-rotation-x)
                    (reset! rotation-y @saved-rotation-y)
                    (reset! animation-state :rotating))
                  [(lerp 0.0 @saved-rotation-x p)
                   (lerp @target-rotation-y @saved-rotation-y p)
                   (lerp (:zoomed-in zooms) (:rotating zooms) p)]))]

          (reset! last-frame-time current-time)

          (GLES20/glClear (bit-or GLES20/GL_COLOR_BUFFER_BIT GLES20/GL_DEPTH_BUFFER_BIT))
          (GLES20/glUseProgram @gl-program)

          (Matrix/setLookAtM view-matrix 0 0 0 current-zoom 0 0 0 0 1 0)
          (Matrix/setIdentityM model-matrix 0)
          (Matrix/rotateM model-matrix 0 current-rot-x 1.0 0.0 0.0)
          (Matrix/rotateM model-matrix 0 current-rot-y 0.0 1.0 0.0)

          (let [vp-matrix (float-array 16)]
            (Matrix/multiplyMM vp-matrix 0 projection-matrix 0 view-matrix 0)
            (Matrix/multiplyMM mvp-matrix 0 vp-matrix 0 model-matrix 0))

          (GLES20/glUniformMatrix4fv @mvp-matrix-handle 1 false mvp-matrix 0)

          (GLES20/glEnableVertexAttribArray @position-handle)
          (GLES20/glVertexAttribPointer @position-handle 3 GLES20/GL_FLOAT false (* 3 4) cube-vertices)
          (GLES20/glEnableVertexAttribArray @tex-coord-handle)
          (GLES20/glVertexAttribPointer @tex-coord-handle 2 GLES20/GL_FLOAT false (* 2 4) texture-coords)

          (GLES20/glActiveTexture GLES20/GL_TEXTURE0)
          (GLES20/glBindTexture GLES20/GL_TEXTURE_2D @texture-id)
          (GLES20/glDrawElements GLES20/GL_TRIANGLES (* 6 6) GLES20/GL_UNSIGNED_SHORT draw-order)

          (GLES20/glDisableVertexAttribArray @position-handle)
          (GLES20/glDisableVertexAttribArray @tex-coord-handle))))))

;;; --- UI and Main App Setup ---
(defn create-button [context text on-click]
  (doto (Button. context)
    (.setText text)
    (.setLayoutParams (android.widget.LinearLayout$LayoutParams. 0 ViewGroup$LayoutParams/WRAP_CONTENT 1.0))
    (.setOnClickListener (proxy [View$OnClickListener] [] (onClick [v] (on-click))))))

(defn play-game []
  (debug-log "Play button pressed")
  (reset! auto-random-mode? false)
  (reset! is-playing true))

(defn stop-game []
  (debug-log "Stop button pressed")
  (reset! auto-random-mode? false)
  (reset! is-playing false))

(defn toggle-auto-random []
  (swap! auto-random-mode? not)
  (if @auto-random-mode?
    (do
      (debug-log "Auto mode ON")
      (randomize-grid)
      (reset! is-playing true))
    (do
      (debug-log "Auto mode OFF")
      (reset! is-playing false))))

(defn stop-rotation []
  (when (= @animation-state :rotating)
    (debug-log "Rotation stopped")
    (reset! saved-rotation-x @rotation-x)
    (reset! saved-rotation-y @rotation-y)
    (reset! target-rotation-y 0.0)
    (reset! animation-progress 0.0)
    (reset! animation-state :zooming-in)))

(defn resume-rotation []
  (when (= @animation-state :zoomed-in)
    (debug-log "Rotation resumed")
    (reset! animation-progress 0.0)
    (reset! animation-state :zooming-out)))

(defn -main []
  (debug-log "Starting Conway's Game of Life 3D")

  (let [main-layout (LinearLayout. *context*), gl-surface-view (GLSurfaceView. *context*), button-container (LinearLayout. *context*)
        button-layout-1 (LinearLayout. *context*), button-layout-2 (LinearLayout. *context*), button-layout-3 (LinearLayout. *context*)
        play-button (create-button *context* "Play" play-game), stop-button (create-button *context* "Stop" stop-game), step-button (create-button *context* "Step" step-game)
        auto-button (create-button *context* "Auto" toggle-auto-random), clear-button (create-button *context* "Clear" clear-grid)
        stop-rot-button (create-button *context* "Stop Rot" stop-rotation), resume-rot-button (create-button *context* "Resume Rot" resume-rotation)]

    (.setOrientation main-layout LinearLayout/VERTICAL)

    (.setEGLContextClientVersion gl-surface-view 2)
    (.setRenderer gl-surface-view (create-renderer))
    (.setRenderMode gl-surface-view GLSurfaceView/RENDERMODE_CONTINUOUSLY)
    (.setLayoutParams gl-surface-view (android.widget.LinearLayout$LayoutParams. -1 0 1.0))
    (.setOnTouchListener gl-surface-view
                         (proxy [View$OnTouchListener] []
                           (onTouch [v event]
                             (when (and (= @animation-state :zoomed-in) (= (.getAction event) MotionEvent/ACTION_DOWN))
                               (let [view-width (float (.getWidth v))
                                     view-height (float (.getHeight v))
                                     touch-x (.getX event)
                                     touch-y (.getY event)
                                     [square-size offset-x offset-y]
                                     (if (> view-width view-height)
                                       [view-height (/ (- view-width view-height) 2.0) 0.0]
                                       [view-width 0.0 (/ (- view-height view-width) 2.0)])
                                     
                                     relative-x (- touch-x offset-x)
                                     relative-y (- touch-y offset-y)
                                     
                                     grid-x (int (* grid-size (/ relative-x square-size)))
                                     grid-y (int (* grid-size (/ relative-y square-size)))]
                                 (when (and (>= grid-x 0) (< grid-x grid-size) (>= grid-y 0) (< grid-y grid-size))
                                   (toggle-cell grid-x grid-y))))
                             true)))

    (.setOrientation button-container LinearLayout/VERTICAL)
    (.setOrientation button-layout-1 LinearLayout/HORIZONTAL)
    (.setOrientation button-layout-2 LinearLayout/HORIZONTAL)
    (.setOrientation button-layout-3 LinearLayout/HORIZONTAL)

    (.addView button-layout-1 play-button) (.addView button-layout-1 stop-button) (.addView button-layout-1 step-button)
    (.addView button-layout-2 auto-button) (.addView button-layout-2 clear-button)
    (.addView button-layout-3 stop-rot-button) (.addView button-layout-3 resume-rot-button)

    (.addView button-container button-layout-1) (.addView button-container button-layout-2) (.addView button-container button-layout-3)

    (.addView main-layout gl-surface-view)
    (.addView main-layout button-container)
    (.addView *content-layout* main-layout)

    (let [lifecycle (.. *context* getLifecycle)
          observer (proxy [LifecycleEventObserver] [] (onStateChanged [s e] (condp = (.name e) "ON_PAUSE" (.onPause gl-surface-view) "ON_RESUME" (.onResume gl-surface-view) nil)))]
      (.addObserver lifecycle observer))

    (debug-log "Game of Life 3D initialized")))
