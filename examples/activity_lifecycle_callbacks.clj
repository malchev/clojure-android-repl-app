;; ActivityLifecycleCallbacks callbacks demo
(import '[android.widget TextView LinearLayout Button ScrollView]
        '[android.view View ViewGroup$LayoutParams Gravity]
        '[android.util Log]
        '[android.graphics Color Typeface]
        '[android.content Context]
        '[android.os Bundle]
        '[android.content.res Configuration]
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
(defn on-create [saved-instance-state]
  (log-event "onCreate")
  (when saved-instance-state
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
  (.putInt outState "eventCount" (:event-count @ui-state)))

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

;; Create explicit callback handlers for all lifecycle methods
(defn handle-activity-created [activity bundle]
  (debug-log "onActivityCreated" activity bundle)
  (when (= activity *context*)
    (on-create bundle)))

(defn handle-activity-started [activity]
  (debug-log "onActivityStarted" activity)
  (when (= activity *context*)
    (on-start)))

(defn handle-activity-resumed [activity]
  (debug-log "onActivityResumed" activity)
  (when (= activity *context*)
    (on-resume)))

(defn handle-activity-paused [activity]
  (debug-log "onActivityPaused" activity)
  (when (= activity *context*)
    (on-pause)))

(defn handle-activity-stopped [activity]
  (debug-log "onActivityStopped" activity)
  (when (= activity *context*)
    (on-stop)))

(defn handle-activity-destroyed [activity]
  (debug-log "onActivityDestroyed" activity)
  (when (= activity *context*)
    (on-destroy)))

;; This is the problematic callback - let's handle all possible arities
(defn handle-activity-save-instance-state
  ([activity outState]
   (debug-log "onActivitySaveInstanceState[2]" activity outState)
   (when (= activity *context*)
     (on-save-instance-state outState)))
  ([activity outState persistentState]
   (debug-log "onActivitySaveInstanceState[3]" activity outState persistentState)
   (when (= activity *context*)
     (on-save-instance-state outState))))

;; Pre/Post callbacks with explicit debugging
(defn handle-activity-pre-created [activity bundle]
  (debug-log "onActivityPreCreated" activity bundle))

(defn handle-activity-pre-started [activity]
  (debug-log "onActivityPreStarted" activity))

(defn handle-activity-pre-resumed [activity]
  (debug-log "onActivityPreResumed" activity))

(defn handle-activity-pre-paused [activity]
  (debug-log "onActivityPrePaused" activity))

(defn handle-activity-pre-stopped [activity]
  (debug-log "onActivityPreStopped" activity))

(defn handle-activity-pre-save-instance-state
  ([activity outState]
   (debug-log "onActivityPreSaveInstanceState[2]" activity outState))
  ([activity outState persistentState]
   (debug-log "onActivityPreSaveInstanceState[3]" activity outState persistentState)))

(defn handle-activity-pre-destroyed [activity]
  (debug-log "onActivityPreDestroyed" activity))

(defn handle-activity-post-created [activity bundle]
  (debug-log "onActivityPostCreated" activity bundle))

(defn handle-activity-post-started [activity]
  (debug-log "onActivityPostStarted" activity))

(defn handle-activity-post-resumed [activity]
  (debug-log "onActivityPostResumed" activity))

(defn handle-activity-post-paused [activity]
  (debug-log "onActivityPostPaused" activity))

(defn handle-activity-post-stopped [activity]
  (debug-log "onActivityPostStopped" activity))

(defn handle-activity-post-save-instance-state
  ([activity outState]
   (debug-log "onActivityPostSaveInstanceState[2]" activity outState))
  ([activity outState persistentState]
   (debug-log "onActivityPostSaveInstanceState[3]" activity outState persistentState)))

(defn handle-activity-post-destroyed [activity]
  (debug-log "onActivityPostDestroyed" activity))

;; Component callback handlers
(defn handle-configuration-changed [newConfig]
  (debug-log "onConfigurationChanged" newConfig)
  (on-configuration-changed newConfig))

(defn handle-low-memory []
  (debug-log "onLowMemory")
  (on-low-memory))

(defn handle-trim-memory [level]
  (debug-log "onTrimMemory" level)
  (on-trim-memory level))

;; Now update the register-callbacks function to use these explicit handlers
(defn register-callbacks []
  (Log/i TAG "Registering lifecycle callbacks")
  (let [application (.getApplication *context*)]
    (Log/d TAG (str "Found application: " application))

    ;; Create a more robust proxy for ActivityLifecycleCallbacks
    (let [lifecycle-callbacks
          (proxy [android.app.Application$ActivityLifecycleCallbacks] []
            ;; Main lifecycle methods
            (onActivityCreated [activity bundle]
              (handle-activity-created activity bundle))
            (onActivityStarted [activity]
              (handle-activity-started activity))
            (onActivityResumed [activity]
              (handle-activity-resumed activity))
            (onActivityPaused [activity]
              (handle-activity-paused activity))
            (onActivityStopped [activity]
              (handle-activity-stopped activity))
            (onActivityDestroyed [activity]
              (handle-activity-destroyed activity))

            ;; The problematic method - handle both possible signatures
            ;; This overloading approach works in Clojure to create multimethods in a Java interface
            (onActivitySaveInstanceState
              ([activity outState]
               (handle-activity-save-instance-state activity outState))
              ([activity outState persistentState]
               (handle-activity-save-instance-state activity outState persistentState)))

            ;; Pre lifecycle methods
            (onActivityPreCreated [activity savedInstanceState]
              (handle-activity-pre-created activity savedInstanceState))
            (onActivityPreStarted [activity]
              (handle-activity-pre-started activity))
            (onActivityPreResumed [activity]
              (handle-activity-pre-resumed activity))
            (onActivityPrePaused [activity]
              (handle-activity-pre-paused activity))
            (onActivityPreStopped [activity]
              (handle-activity-pre-stopped activity))
            (onActivityPreSaveInstanceState
              ([activity outState]
               (handle-activity-pre-save-instance-state activity outState))
              ([activity outState persistentState]
               (handle-activity-pre-save-instance-state activity outState persistentState)))
            (onActivityPreDestroyed [activity]
              (handle-activity-pre-destroyed activity))

            ;; Post lifecycle methods
            (onActivityPostCreated [activity savedInstanceState]
              (handle-activity-post-created activity savedInstanceState))
            (onActivityPostStarted [activity]
              (handle-activity-post-started activity))
            (onActivityPostResumed [activity]
              (handle-activity-post-resumed activity))
            (onActivityPostPaused [activity]
              (handle-activity-post-paused activity))
            (onActivityPostStopped [activity]
              (handle-activity-post-stopped activity))
            (onActivityPostSaveInstanceState
              ([activity outState]
               (handle-activity-post-save-instance-state activity outState))
              ([activity outState persistentState]
               (handle-activity-post-save-instance-state activity outState persistentState)))
            (onActivityPostDestroyed [activity]
              (handle-activity-post-destroyed activity)))]

      ;; Register our robust proxy
      (.registerActivityLifecycleCallbacks application lifecycle-callbacks))

    (Log/i TAG "Activity lifecycle callbacks registered")

    ;; Component callbacks remain unchanged
    (.registerComponentCallbacks *context*
      (proxy [android.content.ComponentCallbacks2] []
        (onConfigurationChanged [newConfig]
          (handle-configuration-changed newConfig))
        (onLowMemory []
          (handle-low-memory))
        (onTrimMemory [level]
          (handle-trim-memory level))))

    (Log/i TAG "Component callbacks registered")))

(defn -main []
  (Log/i TAG "App starting")

  ;; Create UI and set as content view
  (let [main-layout (create-ui)]
    (.addView *content-layout* main-layout)

    ;; Log the app directory
    (Log/i TAG (str "Cache directory: " *cache-dir*))

    ;; Register lifecycle callbacks
    (register-callbacks)

    ;; Initial event
    (log-event "Application Initialized")))
