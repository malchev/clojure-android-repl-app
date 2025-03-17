;; Android lifecycle demonstration using AndroidX LifecycleObserver
(import '[android.widget TextView LinearLayout Button ScrollView]
        '[android.view View ViewGroup$LayoutParams Gravity]
        '[android.util Log]
        '[android.graphics Color Typeface]
        '[android.content Context]
        '[android.os Bundle]
        '[android.content.res Configuration]
        '[androidx.lifecycle Lifecycle Lifecycle$Event Lifecycle$State LifecycleObserver LifecycleOwner LifecycleEventObserver]
        '[java.util Date]
        '[java.text SimpleDateFormat])

;; Require clojure.core to get access to core functions
(clojure.core/refer-clojure)

;; Define Logcat tag
(def ^:const TAG "ClojureApp")

;; Format for timestamps
(def date-formatter (SimpleDateFormat. "HH:mm:ss.SSS"))

;; Reference to hold our UI components
(def ui-state (atom {:scroll-view nil
                     :log-layout nil
                     :status-view nil
                     :event-count 0}))

(defn log-event
  "Log event to both Logcat and the UI"
  [event-name]
  (let [timestamp (.format date-formatter (Date.))
        message (str event-name " at " timestamp)
        _ (swap! ui-state update :event-count inc)
        count (:event-count @ui-state)
        status-view (:status-view @ui-state)
        log-layout (:log-layout @ui-state)
        scroll-view (:scroll-view @ui-state)]

    ;; Log to Logcat
    (Log/i TAG message)

    ;; Update status text view
    (when status-view
      (.setText status-view (str "Last event: " event-name)))

    ;; Add new entry to log layout
    (when log-layout
      (let [event-view (TextView. *context*)]
        (.setTextSize event-view 14)
        (.setText event-view (str count ". " message))
        (.setPadding event-view 20 10 20 10)
        (.setTextColor event-view Color/BLACK)

        ;; Alternate background colors for readability
        (if (odd? count)
          (.setBackgroundColor event-view (Color/parseColor "#F0F0F0"))
          (.setBackgroundColor event-view (Color/parseColor "#FFFFFF")))

        (.addView log-layout event-view)

        ;; Scroll to bottom
        (when scroll-view
          (.post scroll-view (fn [] (.fullScroll scroll-view View/FOCUS_DOWN))))))))

(defn create-ui
  "Create the main UI for the app"
  []
  (let [main-layout (LinearLayout. *context*)
        _ (.setOrientation main-layout LinearLayout/VERTICAL)

        ;; Status bar at top
        status-view (TextView. *context*)
        _ (.setTextSize status-view 18)
        _ (.setTypeface status-view Typeface/DEFAULT_BOLD)
        _ (.setPadding status-view 20 20 20 20)
        _ (.setBackgroundColor status-view (Color/parseColor "#4CAF50"))
        _ (.setTextColor status-view Color/BLACK)
        _ (.setText status-view "App Started")
        _ (.setGravity status-view Gravity/CENTER)
        status-params (android.widget.LinearLayout$LayoutParams.
                       ViewGroup$LayoutParams/MATCH_PARENT
                       ViewGroup$LayoutParams/WRAP_CONTENT)
        _ (.addView main-layout status-view status-params)

        ;; Clear log button
        clear-button (Button. *context*)
        _ (.setText clear-button "Clear Event Log")
        _ (.setOnClickListener clear-button
                             (reify android.view.View$OnClickListener
                               (onClick [this view]
                                 (let [log-layout (:log-layout @ui-state)]
                                   (.removeAllViews log-layout)
                                   (swap! ui-state assoc :event-count 0)
                                   (log-event "Log Cleared")))))
        button-params (android.widget.LinearLayout$LayoutParams.
                      ViewGroup$LayoutParams/MATCH_PARENT
                      ViewGroup$LayoutParams/WRAP_CONTENT)
        _ (.setMargins button-params 20 10 20 10)
        _ (.addView main-layout clear-button button-params)

        ;; Scrollable log area
        scroll-view (ScrollView. *context*)
        scroll-params (android.widget.LinearLayout$LayoutParams.
                      ViewGroup$LayoutParams/MATCH_PARENT
                      ViewGroup$LayoutParams/MATCH_PARENT)
        _ (.setLayoutParams scroll-view scroll-params)

        log-layout (LinearLayout. *context*)
        _ (.setOrientation log-layout LinearLayout/VERTICAL)
        log-params (android.widget.LinearLayout$LayoutParams.
                   ViewGroup$LayoutParams/MATCH_PARENT
                   ViewGroup$LayoutParams/MATCH_PARENT)
        _ (.addView scroll-view log-layout log-params)
        _ (.addView main-layout scroll-view scroll-params)]

    ;; Store references
    (swap! ui-state assoc
           :status-view status-view
           :log-layout log-layout
           :scroll-view scroll-view)

    main-layout))

;; Debug logger
(defn debug-log [method & args]
  (Log/d TAG (str "DEBUG: " method " called with args: " (pr-str args))))

;; Define lifecycle event implementations
(defn on-create [savedInstanceState]
  (log-event "onCreate")
  (when savedInstanceState
    (log-event "Restoring from previous state")))

(defn on-start []
  (log-event "onStart")
  (let [status-view (:status-view @ui-state)]
    (when status-view
      (.setBackgroundColor status-view (Color/parseColor "#2196F3")))))

(defn on-resume []
  (log-event "onResume")
  (let [status-view (:status-view @ui-state)]
    (when status-view
      (.setBackgroundColor status-view (Color/parseColor "#4CAF50")))))

(defn on-pause []
  (log-event "onPause")
  (let [status-view (:status-view @ui-state)]
    (when status-view
      (.setBackgroundColor status-view (Color/parseColor "#FFC107")))))

(defn on-stop []
  (log-event "onStop")
  (let [status-view (:status-view @ui-state)]
    (when status-view
      (.setBackgroundColor status-view (Color/parseColor "#FF5722")))))

(defn on-destroy []
  (log-event "onDestroy")
  (let [status-view (:status-view @ui-state)]
    (when status-view
      (.setBackgroundColor status-view (Color/parseColor "#F44336")))))

(defn on-save-instance-state [outState]
  (log-event "onSaveInstanceState")
  (when outState
    (.putInt outState "eventCount" (:event-count @ui-state))))

(defn on-restore-instance-state [savedInstanceState]
  (log-event "onRestoreInstanceState")
  (when savedInstanceState
    (let [saved-count (.getInt savedInstanceState "eventCount" 0)]
      (swap! ui-state assoc :event-count saved-count)
      (log-event (str "Restored event count: " saved-count)))))

(defn on-configuration-changed [newConfig]
  (log-event (str "onConfigurationChanged: "
                (if (= (.orientation newConfig) Configuration/ORIENTATION_LANDSCAPE)
                  "landscape"
                  "portrait"))))

(defn on-low-memory []
  (log-event "onLowMemory"))

(defn on-trim-memory [level]
  (log-event (str "onTrimMemory: level " level)))

;; Create a simple lifecycle event handler
(defn handle-lifecycle-event [event]
  (debug-log "handle-lifecycle-event" event)
  (case (.name event)
    "ON_CREATE" (on-create nil)
    "ON_START" (on-start)
    "ON_RESUME" (on-resume)
    "ON_PAUSE" (on-pause)
    "ON_STOP" (on-stop) 
    "ON_DESTROY" (on-destroy)
    (log-event (str "Unknown lifecycle event: " event))))

;; Simplified approach to register lifecycle callbacks
(defn register-lifecycle-callbacks []
  (Log/i TAG "Registering lifecycle callbacks")
  
  (let [activity *context*
        lifecycle (.. activity (getLifecycle))]
    
    ;; Log the current state
    (let [state (.getCurrentState lifecycle)]
      (log-event (str "Current lifecycle state: " state)))
    
    ;; Create a LifecycleEventObserver which has the onStateChanged method
    (let [observer (proxy [LifecycleEventObserver] []
                     (onStateChanged [source event]
                       (debug-log "Lifecycle event" event)
                       (handle-lifecycle-event event)))]
      
      ;; Register our observer
      (.addObserver lifecycle observer)
      (log-event "Lifecycle observer registered"))))

;; Register the lifecycle observer and component callbacks
(defn register-callbacks []
  (Log/i TAG "Registering all callbacks")
  
  ;; Set up lifecycle monitoring - simpler approach
  (try
    (register-lifecycle-callbacks)
    (catch Exception e
      (Log/e TAG (str "Error registering lifecycle callbacks: " (.getMessage e)))
      (log-event (str "Lifecycle registration error: " (.getMessage e)))))

  ;; Component callbacks remain the same as before
  (.registerComponentCallbacks *context*
    (proxy [android.content.ComponentCallbacks2] []
      (onConfigurationChanged [newConfig]
        (debug-log "onConfigurationChanged" newConfig)
        (on-configuration-changed newConfig))
      (onLowMemory []
        (debug-log "onLowMemory")
        (on-low-memory))
      (onTrimMemory [level]
        (debug-log "onTrimMemory" level)
        (on-trim-memory level))))

  (Log/i TAG "Component callbacks registered"))

;; Save instance state handler
(defn register-saved-state-handler []
  (Log/i TAG "Setting up saved state handling")
  
  ;; Note: With AndroidX LifecycleObserver, we'd typically use SavedStateRegistry
  ;; But for simplicity in this demo, we'll override onSaveInstanceState in a simpler way
  ;; by adding a listener to the activity if needed
  
  ;; This approach is simplified for the demo.
  ;; In a production app, you would use SavedStateRegistry from androidx.savedstate
  (log-event "SavedState handler configured"))

(defn -main []
  (Log/i TAG "App starting")

  ;; Create UI and set as content view
  (let [main-layout (create-ui)]
    (.addView *content-layout* main-layout)

    ;; Log the app directory
    (Log/i TAG (str "Cache directory: " *cache-dir*))

    ;; Register lifecycle callbacks using AndroidX approach
    (register-callbacks)
    
    ;; Set up saved state handling
    (register-saved-state-handler)

    ;; Initial event
    (log-event "Application Initialized with AndroidX LifecycleObserver"))) 