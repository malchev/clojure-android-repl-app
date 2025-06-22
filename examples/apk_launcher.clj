;; App to enumerate all installed applications on the device and launch them on tap

(def TAG "ClojureApp")

(import '(android.widget ListView ArrayAdapter LinearLayout TextView)
        '(android.widget LinearLayout$LayoutParams)
        '(android.widget AdapterView$OnItemClickListener)
        '(android.content.pm PackageManager ApplicationInfo)
        '(android.content Intent)
        '(android.util Log)
        '(android.graphics Color)
        '(androidx.lifecycle LifecycleEventObserver Lifecycle$Event)
        '(java.util ArrayList Collections Comparator HashMap))

(defn debug-log [msg]
  (Log/d TAG (str msg)))

(defn get-installed-apps []
  (try
    (debug-log "Getting installed applications...")
    (let [pm (.getPackageManager *context*)
          apps (.getInstalledApplications pm PackageManager/GET_META_DATA)
          app-data (ArrayList.)
          app-map (HashMap.)]
      (doseq [app apps]
        (try
          (let [label (.getApplicationLabel pm app)
                label-str (str label)]
            (.add app-data label-str)
            (.put app-map label-str app))
          (catch Exception e
            (debug-log (str "Error getting label for app: " (.getMessage e))))))
      
      (Collections/sort app-data (proxy [Comparator] []
                                   (compare [a b]
                                     (.compareToIgnoreCase a b))))
      
      (debug-log (str "Found " (.size app-data) " applications"))
      {:names app-data :map app-map})
    (catch Exception e
      (debug-log (str "Error getting installed apps: " (.getMessage e)))
      {:names (ArrayList.) :map (HashMap.)})))

(defn launch-app [package-name]
  (try
    (debug-log (str "Attempting to launch app: " package-name))
    (let [pm (.getPackageManager *context*)
          intent (.getLaunchIntentForPackage pm package-name)]
      (if intent
        (do
          (.startActivity *context* intent)
          (debug-log (str "Successfully launched: " package-name)))
        (debug-log (str "No launch intent found for: " package-name))))
    (catch Exception e
      (debug-log (str "Error launching app " package-name ": " (.getMessage e))))))

(defn create-item-click-listener [app-map]
  (proxy [AdapterView$OnItemClickListener] []
    (onItemClick [parent view position id]
      (try
        (let [app-name (.getItemAtPosition parent position)
              app-info (.get app-map app-name)
              package-name (.-packageName app-info)]
          (debug-log (str "Clicked on: " app-name " (package: " package-name ")"))
          (launch-app package-name))
        (catch Exception e
          (debug-log (str "Error handling item click: " (.getMessage e))))))))

(defn create-app-list []
  (try
    (debug-log "Creating app list view...")
    (let [app-data (get-installed-apps)
          app-names (:names app-data)
          app-map (:map app-data)
          list-view (ListView. *context*)
          adapter (ArrayAdapter. *context* 
                                android.R$layout/simple_list_item_1 
                                app-names)
          click-listener (create-item-click-listener app-map)]
      (.setAdapter list-view adapter)
      (.setOnItemClickListener list-view click-listener)
      (debug-log "App list created with click listener")
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
      
      (.setText title "Installed Applications (Tap to Launch)")
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
    (debug-log "Starting Installed Apps Enumerator with Launch Capability")
    (debug-log (str "Context: " *context*))
    (debug-log (str "Content layout: " *content-layout*))
    (debug-log (str "Cache dir: " *cache-dir*))
    
    (setup-lifecycle)
    (debug-log "App initialization complete")
    (catch Exception e
      (debug-log (str "Error in -main: " (.getMessage e))))))
