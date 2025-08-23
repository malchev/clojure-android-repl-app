;; A simple camera app that shows a preview and allows taking a snapshot.
(let [log-tag "ClojureApp"
      REQUEST_CAMERA_PERMISSION 200]

  (import android.Manifest$permission
          android.app.Activity
          android.content.Context
          android.content.pm.PackageManager
          android.graphics.Bitmap
          android.graphics.BitmapFactory
          android.graphics.Color
          android.graphics.ImageFormat
          android.graphics.SurfaceTexture
          android.hardware.camera2.CameraAccessException
          android.hardware.camera2.CameraCaptureSession
          android.hardware.camera2.CameraCharacteristics
          android.hardware.camera2.CameraDevice
          android.hardware.camera2.CameraManager
          android.hardware.camera2.CaptureRequest
          android.hardware.camera2.TotalCaptureResult
          android.media.ImageReader
          android.os.Handler
          android.os.HandlerThread
          android.util.Log
          android.util.Size
          android.view.Gravity
          android.view.Surface
          android.view.TextureView
          android.view.View
          android.widget.Button
          android.widget.ImageView
          android.widget.LinearLayout
          android.widget.LinearLayout$LayoutParams
          android.widget.Toast
          androidx.core.app.ActivityCompat
          androidx.core.content.ContextCompat
          androidx.lifecycle.Lifecycle$Event
          androidx.lifecycle.LifecycleEventObserver)

  ;; --- State Management ---
  (defonce texture-view (atom nil))
  (defonce image-view (atom nil))
  (defonce camera-id (atom nil))
  (defonce camera-device (atom nil))
  (defonce capture-session (atom nil))
  (defonce capture-request-builder (atom nil))
  (defonce image-reader (atom nil))
  (defonce background-thread (atom nil))
  (defonce background-handler (atom nil))

  ;; --- Utility Functions ---
  (defn- log-d [& msg]
    (Log/d log-tag (apply str msg)))

  (defn- show-toast [text]
    (.runOnUiThread *context*
      #(Toast/makeText *context* (str text) Toast/LENGTH_LONG)))

  ;; --- Background Thread Management ---
  (defn- start-background-thread []
    (log-d "Starting background thread")
    (let [thread (HandlerThread. "CameraBackground")]
      (.start thread)
      (reset! background-thread thread)
      (reset! background-handler (Handler. (.getLooper thread)))))

  (defn- stop-background-thread []
    (log-d "Stopping background thread")
    (when-let [thread @background-thread]
      (.quitSafely thread)
      (try
        (.join thread)
        (catch InterruptedException e (log-d "Error stopping thread: " e)))
      (reset! background-thread nil)
      (reset! background-handler nil)))

  ;; --- Forward Declarations for Inter-dependent Functions ---
  (declare open-camera)
  (declare create-camera-preview-session)

  ;; --- Camera Callbacks ---
  (def camera-device-state-callback
    (proxy [CameraDevice$StateCallback] []
      (onOpened [camera]
        (log-d "CameraDevice.onOpened")
        (reset! camera-device camera)
        (create-camera-preview-session))
      (onDisconnected [camera]
        (log-d "CameraDevice.onDisconnected")
        (.close camera)
        (reset! camera-device nil))
      (onError [camera error]
        (log-d "CameraDevice.onError: " error)
        (.close camera)
        (reset! camera-device nil)
        (.finish *context*))))

  (def capture-session-state-callback
    (proxy [CameraCaptureSession$StateCallback] []
      (onConfigured [session]
        (log-d "CaptureSession.onConfigured")
        (when @camera-device
          (reset! capture-session session)
          (try
            (.setRepeatingRequest session
                                  (.build @capture-request-builder)
                                  nil
                                  @background-handler)
            (catch CameraAccessException e
              (log-d "Failed to start preview: " e)))))
      (onConfigureFailed [session]
        (log-d "CaptureSession.onConfigureFailed")
        (show-toast "Failed to configure camera"))))

  (def on-image-available-listener
    (proxy [ImageReader$OnImageAvailableListener] []
      (onImageAvailable [reader]
        (log-d "Image available")
        (let [image (.acquireLatestImage reader)
              buffer (.getPlanes image)
              bytes (byte-array (.remaining (aget buffer 0)))]
          (-> (aget buffer 0) (.get bytes))
          (.close image)
          (let [bitmap (BitmapFactory/decodeByteArray bytes 0 (count bytes))]
            (.runOnUiThread *context*
              #(when-let [iv @image-view]
                 (.setImageBitmap iv bitmap)
                 (.setVisibility iv View/VISIBLE))))))))

  ;; --- Core Camera Logic ---
  (defn- close-camera []
    (log-d "Closing camera")
    (try
      (when-let [session @capture-session]
        (.close session)
        (reset! capture-session nil))
      (when-let [device @camera-device]
        (.close device)
        (reset! camera-device nil))
      (when-let [reader @image-reader]
        (.close reader)
        (reset! image-reader nil))
      (catch Exception e
        (log-d "Error closing camera resources: " e))))

  (defn- setup-camera-outputs [width height]
    (let [manager (.getSystemService *context* Context/CAMERA_SERVICE)]
      (try
        (doseq [id (.getCameraIdList manager)]
          (let [characteristics (.getCameraCharacteristics manager id)
                facing (.get characteristics CameraCharacteristics/LENS_FACING)]
            (when (= facing CameraCharacteristics/LENS_FACING_BACK)
              (log-d "Found back-facing camera: " id)
              (reset! camera-id id)
              (let [map (.get characteristics CameraCharacteristics/SCALER_STREAM_CONFIGURATION_MAP)
                    jpeg-sizes (.getOutputSizes map ImageFormat/JPEG)
                    largest-jpeg-size (first (sort-by #(* (.getWidth %) (.getHeight %)) > jpeg-sizes))]
                (log-d "Largest JPEG size: " largest-jpeg-size)
                (let [reader (ImageReader/newInstance (.getWidth largest-jpeg-size)
                                                      (.getHeight largest-jpeg-size)
                                                      ImageFormat/JPEG
                                                      1)]
                  (.setOnImageAvailableListener reader on-image-available-listener @background-handler)
                  (reset! image-reader reader)))
              (break))))
        (catch CameraAccessException e
          (log-d "Camera access exception in setup: " e)))))

  (defn- open-camera [width height]
    (if (not= (ContextCompat/checkSelfPermission *context* permission/CAMERA)
              PackageManager/PERMISSION_GRANTED)
      (do
        (log-d "Camera permission not granted. Cannot open camera.")
        (show-toast "Camera permission is required."))
      (do
        (log-d "Opening camera...")
        (setup-camera-outputs width height)
        (let [manager (.getSystemService *context* Context/CAMERA_SERVICE)]
          (try
            (.openCamera manager @camera-id camera-device-state-callback @background-handler)
            (catch CameraAccessException e (log-d "Failed to open camera: " e))
            (catch SecurityException e (log-d "Security exception opening camera: " e)))))))

  (defn- create-camera-preview-session []
    (log-d "Creating camera preview session")
    (try
      (let [texture (-> @texture-view .getSurfaceTexture)
            _ (.setDefaultBufferSize texture 1920 1080) ; A reasonable default
            surface (Surface. texture)
            builder (.createCaptureRequest @camera-device CameraDevice/TEMPLATE_PREVIEW)]
        (.addTarget builder surface)
        (reset! capture-request-builder builder)
        (.createCaptureSession @camera-device
                               (list surface (.getSurface @image-reader))
                               capture-session-state-callback
                               nil))
      (catch CameraAccessException e
        (log-d "Error creating preview session: " e))))

  (defn- take-picture []
    (log-d "take-picture called")
    (when-let [device @camera-device]
      (try
        (let [capture-builder (.createCaptureRequest device CameraDevice/TEMPLATE_STILL_CAPTURE)]
          (.addTarget capture-builder (.getSurface @image-reader))
          (.set capture-builder CaptureRequest/CONTROL_AF_MODE CaptureRequest/CONTROL_AF_MODE_CONTINUOUS_PICTURE)
          ;; TODO: Add orientation handling
          (let [capture-callback (proxy [CameraCaptureSession$CaptureCallback] []
                                   (onCaptureCompleted [session request result]
                                     (log-d "Capture completed.")
                                     (show-toast "Image captured!")))]
            (.capture @capture-session capture-builder capture-callback nil)))
        (catch CameraAccessException e
          (log-d "Error taking picture: " e)))))

  ;; --- UI and Lifecycle Callbacks ---
  (def texture-view-listener
    (proxy [TextureView$SurfaceTextureListener] []
      (onSurfaceTextureAvailable [surface width height]
        (log-d "SurfaceTexture available. Opening camera.")
        (open-camera width height))
      (onSurfaceTextureSizeChanged [surface width height]
        (log-d "SurfaceTexture size changed."))
      (onSurfaceTextureDestroyed [surface]
        (log-d "SurfaceTexture destroyed.")
        true)
      (onSurfaceTextureUpdated [surface]
        )))

  (defn- handle-lifecycle-event [source event]
    (log-d "Lifecycle Event: " (.name event))
    (cond
      (= event Lifecycle$Event/ON_CREATE)
      (when (not= (ContextCompat/checkSelfPermission *context* permission/CAMERA)
                  PackageManager/PERMISSION_GRANTED)
        (log-d "Requesting camera permission")
        (ActivityCompat/requestPermissions *context* (into-array [permission/CAMERA]) REQUEST_CAMERA_PERMISSION))

      (= event Lifecycle$Event/ON_RESUME)
      (do
        (start-background-thread)
        (when-let [tv @texture-view]
          (if (.isAvailable tv)
            (open-camera (.getWidth tv) (.getHeight tv))
            (.setSurfaceTextureListener tv texture-view-listener))))

      (= event Lifecycle$Event/ON_PAUSE)
      (do
        (close-camera)
        (stop-background-thread))
      
      :else
      (log-d "Unhandled lifecycle event: " (.name event))))

  ;; --- Main App Entry Point ---
  (defn -main []
    (log-d "Clojure app -main function started.")
    (.removeAllViews *content-layout*)
    (.setOrientation *content-layout* LinearLayout/VERTICAL)
    (.setBackgroundColor *content-layout* Color/WHITE)

    (let [tv (TextureView. *context*)
          iv (ImageView. *context*)
          capture-button (Button. *context*)
          lp-preview (LinearLayout$LayoutParams. LinearLayout$LayoutParams/MATCH_PARENT 0 1.0)
          lp-button (LinearLayout$LayoutParams. LinearLayout$LayoutParams/MATCH_PARENT LinearLayout$LayoutParams/WRAP_CONTENT)]

      (reset! texture-view tv)
      (reset! image-view iv)

      (.setLayoutParams tv lp-preview)
      (.setSurfaceTextureListener tv texture-view-listener)

      (.setText capture-button "Take Picture")
      (.setLayoutParams capture-button lp-button)
      (.setOnClickListener capture-button
        (reify View$OnClickListener
          (onClick [v] (take-picture))))

      (.setLayoutParams iv lp-preview)
      (.setVisibility iv View/GONE)
      (.setScaleType iv ImageView$ScaleType/FIT_CENTER)

      (.addView *content-layout* tv)
      (.addView *content-layout* capture-button)
      (.addView *content-layout* iv)

      (try
        (let [lifecycle (.. *context* getLifecycle)
              observer (proxy [LifecycleEventObserver] []
                         (onStateChanged [source event]
                           (handle-lifecycle-event source event)))]
          (.addObserver lifecycle observer)
          (log-d "Lifecycle observer added."))
        (catch Exception e
          (log-d "Error setting up lifecycle observer: " e))))
    (log-d "-main function finished.")))
