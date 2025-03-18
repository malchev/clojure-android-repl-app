;; AndroidX LifecycleObserver demo
(import '[android.widget TextView LinearLayout Button ScrollView]
        '[android.view View ViewGroup$LayoutParams Gravity]
        '[android.util Log]
        '[android.graphics Color Typeface]
        '[android.content Context]
        '[android.os Bundle]
        '[android.content.res Configuration]
        '[androidx.lifecycle Lifecycle Lifecycle$Event Lifecycle$State LifecycleObserver LifecycleOwner LifecycleEventObserver]
        '[java.util Date]
        '[java.text SimpleDateFormat]
        '[java.io File FileWriter BufferedWriter FileReader BufferedReader]
        '[org.json JSONObject JSONArray])

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
                     :event-count 0
                     :log-entries []
                     :restored-logs-count 0}))

;; File for saving logs - use a different filename for this implementation
(def log-file-path (str *cache-dir* "/androidx_lifecycle_logs.json"))

;; Add a session identifier to clearly separate logs from different runs
(def session-id (str (System/currentTimeMillis)))

;; Add a flag to mark the first app launch
(def first-launch (atom true))

;; First, add this global counter to keep track of the highest count seen
(def highest-seen-count (atom 0))

;; Initialize the counter based on existing log file as early as possible
(try
  (let [file (File. log-file-path)]
    (when (.exists file)
      (try
        (let [reader (BufferedReader. (FileReader. file))
              sb (StringBuilder.)
              buffer (char-array 1024)
              _ (loop [chars-read (.read reader buffer 0 1024)]
                  (when (> chars-read 0)
                    (.append sb buffer 0 chars-read)
                    (recur (.read reader buffer 0 1024))))
              _ (.close reader)
              json-str (.toString sb)]

          (when (> (.length json-str) 0)
            (let [json-array (JSONArray. json-str)
                  max-count (atom 0)]

              ;; Find the max count in the JSON array
              (dotimes [i (.length json-array)]
                (try
                  (let [json-obj (.getJSONObject json-array i)
                        count (try (.getInt json-obj "count") (catch Exception e 0))]
                    (when (> count @max-count)
                      (reset! max-count count)))
                  (catch Exception e nil)))

              ;; Set the highest-seen-count to the max found
              (swap! highest-seen-count max @max-count))))
        (catch Exception e nil))))
  (catch Exception e nil))

(defn save-logs-to-file
  "Save logs to a file in JSON format"
  []
  (try
    (let [log-entries (:log-entries @ui-state)
          json-array (JSONArray.)]

      ;; Add each log entry to the JSON array
      (doseq [entry log-entries]
        (let [json-obj (JSONObject.)]
          (.put json-obj "message" (:message entry))
          (.put json-obj "timestamp" (:timestamp entry))
          (.put json-obj "restored" (boolean (:restored entry)))
          (.put json-obj "count" (:count entry))
          (.put json-obj "event-name" (:event-name entry))
          (.put json-obj "background-color" (:background-color entry))
          (.put json-obj "session-id" (:session-id entry))
          (.put json-array json-obj)))

      ;; Write to file
      (let [writer (BufferedWriter. (FileWriter. log-file-path))]
        (.write writer (.toString json-array))
        (.close writer)
        (Log/i TAG (str "Logs saved to " log-file-path))))
    (catch Exception e
      (Log/e TAG (str "Error saving logs: " (.getMessage e))))))

(defn load-logs-from-file
  "Load logs from file in JSON format"
  []
  (try
    (let [file (File. log-file-path)]
      (Log/d TAG (str "Checking for log file at: " log-file-path))
      (if (.exists file)
        (try
          (Log/d TAG (str "Log file exists, size: " (.length file) " bytes"))
          (let [reader (BufferedReader. (FileReader. file))
                sb (StringBuilder.)
                buffer (char-array 1024)
                _ (loop [chars-read (.read reader buffer 0 1024)]
                    (when (> chars-read 0)
                      (.append sb buffer 0 chars-read)
                      (recur (.read reader buffer 0 1024))))
                _ (.close reader)
                json-str (.toString sb)]

            (Log/d TAG (str "Read " (.length json-str) " characters from log file"))

            (if (> (.length json-str) 0)
              (try
                (let [json-array (JSONArray. json-str)
                      log-entries (atom [])
                      restored-count (.length json-array)]

                  (Log/d TAG (str "Parsed JSON array with " restored-count " entries"))

                  ;; Convert JSON array back to log entries with careful error handling
                  (dotimes [i restored-count]
                    (try
                      (let [json-obj (.getJSONObject json-array i)
                            session-id-exists (try
                                               (.has json-obj "session-id")
                                               (catch Exception e false))
                            entry {:message (try (.getString json-obj "message") (catch Exception e "Unknown message"))
                                   :timestamp (try (.getString json-obj "timestamp") (catch Exception e "Unknown time"))
                                   :count (try (.getInt json-obj "count") (catch Exception e i))
                                   :event-name (try (.getString json-obj "event-name") (catch Exception e "Unknown event"))
                                   :background-color (try (.getString json-obj "background-color") (catch Exception e "#FFFFFF"))
                                   :restored true
                                   :session-id (if session-id-exists
                                                (.getString json-obj "session-id")
                                                "old-session")}]
                        (swap! log-entries conj entry))
                      (catch Exception e
                        (Log/e TAG (str "Error processing log entry " i ": " (.getMessage e))))))

                  ;; Return the loaded entries and count
                  {:entries @log-entries
                   :count restored-count})
                (catch Exception e
                  (Log/e TAG (str "Error parsing JSON: " (.getMessage e)))
                  {:entries [] :count 0}))
              (do
                (Log/w TAG "Log file is empty")
                {:entries [] :count 0})))
          (catch Exception e
            (Log/e TAG (str "Error reading log file: " (.getMessage e)))
            {:entries [] :count 0}))
        (do
          (Log/d TAG "Log file does not exist")
          {:entries [] :count 0})))
    (catch Exception e
      (Log/e TAG (str "Error in load-logs-from-file: " (.getMessage e)))
      {:entries [] :count 0})))

(defn display-log-entry
  "Display a single log entry in the UI"
  [entry]
  (let [log-layout (:log-layout @ui-state)
        event-view (TextView. *context*)]

    (.setTextSize event-view 14)
    (.setText event-view (str (:count entry) ". " (:message entry)))
    (.setPadding event-view 20 10 20 10)
    (.setTextColor event-view Color/BLACK)

    ;; Set background color - different for restored logs
    (if (:restored entry)
      (.setBackgroundColor event-view (Color/parseColor "#E1F5FE")) ;; Light blue for restored logs
      (.setBackgroundColor event-view (Color/parseColor
                                       (if (odd? (:count entry))
                                         "#F0F0F0"
                                         "#FFFFFF"))))

    ;; Add a left border to restored logs for visual separation
    (when (:restored entry)
      (let [drawable (android.graphics.drawable.GradientDrawable.)]
        (.setStroke drawable 5 (Color/parseColor "#2196F3"))
        (.setBackground event-view drawable)))

    (.addView log-layout event-view)))

(defn log-event
  "Log event to both Logcat and the UI"
  [event-name]
  (let [timestamp (.format date-formatter (Date.))
        message (str event-name " at " timestamp)

        ;; Get next count by incrementing our highest-seen-count
        _ (swap! highest-seen-count inc)
        count @highest-seen-count

        ;; Update UI state with the new count
        _ (swap! ui-state assoc :event-count count)

        status-view (:status-view @ui-state)
        log-layout (:log-layout @ui-state)
        scroll-view (:scroll-view @ui-state)

        ;; Create a log entry record with session ID and unique count
        entry {:message message
               :timestamp timestamp
               :count count
               :event-name event-name
               :background-color (if (odd? count) "#F0F0F0" "#FFFFFF")
               :restored false
               :session-id session-id}]

    ;; Log to Logcat
    (Log/i TAG message)

    ;; Update status text view
    (when status-view
      (.setText status-view (str "Last event: " event-name)))

    ;; Add to our in-memory log collection
    (swap! ui-state update :log-entries conj entry)

    ;; Add new entry to log layout - display all restored entries first
    (when log-layout
      (if @first-launch
        ;; During first launch, don't display right away - wait for main to finish
        ;; This will allow restored logs to be displayed first
        (Log/d TAG "Deferring display of log entry during startup")

        ;; After startup, display entries immediately
        (display-log-entry entry))

      ;; Only save logs after initial startup sequence is complete
      ;; or for special events that should always be saved
      (when (or (not @first-launch)
                (= event-name "App Initialized"))
        (save-logs-to-file))

      (when scroll-view
        (.post scroll-view
               (fn []
                 (.fullScroll scroll-view View/FOCUS_DOWN)))))))

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

        ;; Buttons container
        button-container (LinearLayout. *context*)
        _ (.setOrientation button-container LinearLayout/HORIZONTAL)
        container-params (android.widget.LinearLayout$LayoutParams.
                         ViewGroup$LayoutParams/MATCH_PARENT
                         ViewGroup$LayoutParams/WRAP_CONTENT)
        _ (.addView main-layout button-container container-params)

        ;; Clear log button
        clear-button (Button. *context*)
        _ (.setText clear-button "Clear Event Log")
        _ (.setOnClickListener clear-button
                             (reify android.view.View$OnClickListener
                               (onClick [this view]
                                 (let [log-layout (:log-layout @ui-state)]
                                   (.removeAllViews log-layout)
                                   (swap! ui-state assoc
                                          :event-count 0
                                          :log-entries []
                                          :restored-logs-count 0)
                                   ;; Delete the log file when clearing
                                   (let [file (File. log-file-path)]
                                     (when (.exists file)
                                       (.delete file)))
                                   (log-event "Log Cleared")))))
        button-params (android.widget.LinearLayout$LayoutParams.
                      0
                      ViewGroup$LayoutParams/WRAP_CONTENT)
        _ (set! (.weight button-params) 1.0)
        _ (.setMargins button-params 10 10 5 10)
        _ (.addView button-container clear-button button-params)

        ;; Add legend for restored logs
        restored-legend (TextView. *context*)
        _ (.setText restored-legend "■ Restored logs")
        _ (.setTextSize restored-legend 14)
        _ (.setTextColor restored-legend (Color/parseColor "#2196F3"))
        _ (.setPadding restored-legend 20 10 20 10)
        _ (.setTypeface restored-legend Typeface/DEFAULT_BOLD)
        legend-params (android.widget.LinearLayout$LayoutParams.
                      ViewGroup$LayoutParams/WRAP_CONTENT
                      ViewGroup$LayoutParams/WRAP_CONTENT)
        _ (.setMargins legend-params 10 10 10 10)
        _ (.addView main-layout restored-legend legend-params)

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
    (.putInt outState "eventCount" (:event-count @ui-state))
    ;; Make sure to save logs to file
    (save-logs-to-file)))

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

;; Modify restore-logs to update the highest-seen-count from restored entries
(defn restore-logs
  "Restore logs from previous sessions"
  []
  (let [{:keys [entries count]} (load-logs-from-file)]
    (when (> count 0)
      ;; Log the restore event (will use next available count after our initialization)
      (log-event (str "Restored " count " logs from previous session"))
      (swap! ui-state assoc :restored-logs-count count)

      ;; Add restored entries to our collection but don't display yet
      (swap! ui-state update :log-entries concat entries))))

;; Add this utility function for debugging
(defn delete-log-file
  "Delete the log file - useful for debugging"
  []
  (try
    (let [file (File. log-file-path)]
      (when (.exists file)
        (let [deleted (.delete file)]
          (Log/i TAG (str "Log file deletion " (if deleted "successful" "failed"))))))
    (catch Exception e
      (Log/e TAG (str "Error deleting log file: " (.getMessage e))))))

;; Update the main function to restore logs when app starts
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

    ;; Restore logs from previous sessions FIRST
    ;; This will update event-count appropriately
    (restore-logs)

    ;; Now that restore has set the proper event count,
    ;; log the initialization event which will get a count higher than any restored log
    (log-event "Application Initialized with AndroidX LifecycleObserver")

    ;; Now that all startup events are complete, display all logs in the right order
    (let [log-layout (:log-layout @ui-state)
          all-entries (:log-entries @ui-state)
          ;; First sort by restored status (restored first), then by count
          sorted-entries (sort-by (juxt #(if (:restored %) 0 1) :count) all-entries)]

      ;; Clear the log layout since we'll redisplay everything
      (.removeAllViews log-layout)

      ;; Display all entries in the correct order
      (doseq [entry sorted-entries]
        (display-log-entry entry)))

    ;; Mark the end of first launch startup sequence
    (reset! first-launch false)))
