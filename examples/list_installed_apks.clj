;; App to enumerate all installed applications on the device

(def TAG "ClojureApp")

(import '(android.widget ListView ArrayAdapter LinearLayout TextView)
        '(android.widget LinearLayout$LayoutParams)
        '(android.content.pm PackageManager ApplicationInfo)
        '(android.util Log)
        '(android.graphics Color)
        '(androidx.lifecycle LifecycleEventObserver Lifecycle$Event)
        '(java.util ArrayList Collections Comparator))

(defn debug-log [msg]
  (Log/d TAG (str msg)))

(defn get-installed-apps []
  (try
    (debug-log "Getting installed applications...")
    (let [pm (.getPackageManager *context*)
          apps (.getInstalledApplications pm PackageManager/GET_META_DATA)
          app-names (ArrayList.)]
      (doseq [app apps]
        (try
          (let [label (.getApplicationLabel pm app)]
            (.add app-names (str label)))
          (catch Exception e
            (debug-log (str "Error getting label for app: " (.getMessage e))))))
      
      (Collections/sort app-names (proxy [Comparator] []
                                    (compare [a b]
                                      (.compareToIgnoreCase a b))))
      
      (debug-log (str "Found " (.size app-names) " applications"))
      app-names)
    (catch Exception e
      (debug-log (str "Error getting installed apps: " (.getMessage e)))
      (ArrayList.))))

(defn create-app-list []
  (try
    (debug-log "Creating app list view...")
    (let [apps (get-installed-apps)
          list-view (ListView. *context*)
          adapter (ArrayAdapter. *context* 
                                android.R$layout/simple_list_item_1 
                                apps)]
      (.setAdapter list-view adapter)
      list-view)
    (catch Exception e
      (debug-log (str "Error creating app list: " (.getMessage e)))
      (let [error-text (TextView. *context*)]
        (.setText error-text "Error loading apps")
        error-text))))

(defn setup-ui []
  (try
    (debug-log "Setting up UI...")
    (let [main-layout (LinearLayout. *context*)
          title (TextView. *context*)
          app-list (create-app-list)]
      
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setPadding main-layout 16 16 16 16)
      
      (.setText title "Installed Applications")
      (.setTextSize title 20.0)
      (.setTextColor title Color/BLACK)
      (.setPadding title 0 0 0 16)
      
      (.addView main-layout title)
      (.addView main-layout app-list)
      
      (.setContentView *context* main-layout)
      (debug-log "UI setup complete"))
    (catch Exception e
      (debug-log (str "Error setting up UI: " (.getMessage e))))))

(defn lifecycle-handler [source event]
  (try
    (debug-log (str "Lifecycle event: " (.name event)))
    (case (.name event)
      "ON_CREATE" (do
                    (debug-log "Activity created")
                    (setup-ui))
      "ON_START"  (debug-log "Activity started")
      "ON_RESUME" (debug-log "Activity resumed")
      "ON_PAUSE"  (debug-log "Activity paused")
      "ON_STOP"   (debug-log "Activity stopped")
      "ON_DESTROY" (debug-log "Activity destroyed")
      (debug-log (str "Unhandled lifecycle event: " (.name event))))
    (catch Exception e
      (debug-log (str "Error in lifecycle handler: " (.getMessage e))))))

(defn setup-lifecycle []
  (try
    (debug-log "Setting up lifecycle observer...")
    (let [lifecycle (.. *context* (getLifecycle))
          observer (proxy [LifecycleEventObserver] []
                     (onStateChanged [source event]
                       (lifecycle-handler source event)))]
      (.addObserver lifecycle observer)
      (debug-log "Lifecycle observer registered successfully"))
    (catch Exception e
      (debug-log (str "Error setting up lifecycle: " (.getMessage e))))))

(defn -main []
  (try
    (debug-log "Starting Installed Apps Enumerator")
    (debug-log (str "Context: " *context*))
    (debug-log (str "Content layout: " *content-layout*))
    (debug-log (str "Cache dir: " *cache-dir*))
    
    (setup-lifecycle)
    (debug-log "App initialization complete")
    (catch Exception e
      (debug-log (str "Error in -main: " (.getMessage e))))))
