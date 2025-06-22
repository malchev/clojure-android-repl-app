;; App to enumerate installed applications and show their intents with launch capability

(def TAG "ClojureApp")

(import '(android.widget ListView ArrayAdapter LinearLayout TextView Button ScrollView)
        '(android.widget LinearLayout$LayoutParams)
        '(android.content.pm PackageManager ApplicationInfo ResolveInfo)
        '(android.content Intent)
        '(android.util Log)
        '(android.graphics Color)
        '(android.view View$OnClickListener View ViewGroup)
        '(androidx.lifecycle LifecycleEventObserver Lifecycle$Event)
        '(java.util ArrayList Collections Comparator))

(def current-view (atom :list))
(def selected-app (atom nil))

; Forward declarations for functions with circular dependencies
(declare setup-ui)

(defn debug-log [msg]
  (Log/d TAG (str msg)))

(defn get-installed-apps []
  (try
    (debug-log "Getting installed applications...")
    (let [pm (.getPackageManager *context*)
          apps (.getInstalledApplications pm PackageManager/GET_META_DATA)
          app-info (ArrayList.)]
      (doseq [app apps]
        (try
          (let [label (.getApplicationLabel pm app)]
            (.add app-info {:label (str label) :package-name (.-packageName app)}))
          (catch Exception e
            (debug-log (str "Error getting label for app: " (.getMessage e))))))
      
      (Collections/sort app-info (proxy [Comparator] []
                                   (compare [a b]
                                     (.compareToIgnoreCase (:label a) (:label b)))))
      
      (debug-log (str "Found " (.size app-info) " applications"))
      app-info)
    (catch Exception e
      (debug-log (str "Error getting installed apps: " (.getMessage e)))
      (ArrayList.))))

(defn get-app-intents [package-name]
  (try
    (debug-log (str "Getting intents for package: " package-name))
    (let [pm (.getPackageManager *context*)
          intents (ArrayList.)]
      
      ; Add main launcher intent
      (.add intents "ACTION_MAIN (Launcher)")
      
      ; Try to get other common intents
      (try
        (let [package-info (.getPackageInfo pm package-name PackageManager/GET_ACTIVITIES)]
          (when (.-activities package-info)
            (doseq [activity (.-activities package-info)]
              (when (.-name activity)
                (.add intents (str "Activity: " (.-name activity)))))))
        (catch Exception e
          (debug-log (str "Error getting activities: " (.getMessage e)))))
      
      ; Get intent filters by querying common actions
      (let [common-actions [Intent/ACTION_VIEW Intent/ACTION_SEND Intent/ACTION_EDIT
                           Intent/ACTION_PICK Intent/ACTION_GET_CONTENT]]
        (doseq [action common-actions]
          (try
            (let [intent (Intent. action)
                  resolved (.queryIntentActivities pm intent 0)]
              (doseq [resolve-info resolved]
                (when (= package-name (.. resolve-info -activityInfo -packageName))
                  (.add intents (str "Handles: " action)))))
            (catch Exception e
              (debug-log (str "Error checking action " action ": " (.getMessage e)))))))
      
      (debug-log (str "Found " (.size intents) " intents for " package-name))
      intents)
    (catch Exception e
      (debug-log (str "Error getting intents: " (.getMessage e)))
      (let [error-list (ArrayList.)]
        (.add error-list "Error loading intents")
        error-list))))

(defn launch-app [package-name]
  (try
    (debug-log (str "Launching app: " package-name))
    (let [pm (.getPackageManager *context*)
          launch-intent (.getLaunchIntentForPackage pm package-name)]
      (if launch-intent
        (do
          (.startActivity *context* launch-intent)
          (debug-log (str "Successfully launched " package-name)))
        (debug-log (str "No launch intent found for " package-name))))
    (catch Exception e
      (debug-log (str "Error launching app: " (.getMessage e))))))

(defn back-button-handler []
  (debug-log "Back button pressed")
  (reset! current-view :list)
  (setup-ui))

(defn launch-button-handler []
  (debug-log "Launch button pressed")
  (when @selected-app
    (launch-app (:package-name @selected-app))))

(defn create-custom-adapter [apps]
  (proxy [ArrayAdapter] [*context* android.R$layout/simple_list_item_1 apps]
    (getView [position convertView parent]
      (let [view (if convertView
                   convertView
                   (let [tv (TextView. *context*)]
                     (.setPadding tv 16 16 16 16)
                     tv))
            app (.getItem this position)]
        ; Force black text on white background
        (.setText view (:label app))
        (.setTextColor view Color/BLACK)
        (.setBackgroundColor view Color/WHITE)
        (.setTextSize view 16.0)
        view))))

(defn create-app-list []
  (try
    (debug-log "Creating app list view...")
    (let [apps (get-installed-apps)
          list-view (ListView. *context*)
          adapter (create-custom-adapter apps)]
      
      ; Set white background for list view
      (.setBackgroundColor list-view Color/WHITE)
      (.setAdapter list-view adapter)
      
      (.setOnItemClickListener list-view
        (proxy [android.widget.AdapterView$OnItemClickListener] []
          (onItemClick [parent view position id]
            (try
              (debug-log (str "App selected at position: " position))
              (let [selected (.get apps position)]
                (reset! selected-app selected)
                (reset! current-view :detail)
                (setup-ui))
              (catch Exception e
                (debug-log (str "Error handling item click: " (.getMessage e))))))))
      
      list-view)
    (catch Exception e
      (debug-log (str "Error creating app list: " (.getMessage e)))
      (let [error-text (TextView. *context*)]
        (.setText error-text "Error loading apps")
        (.setTextColor error-text Color/BLACK)
        (.setBackgroundColor error-text Color/WHITE)
        (.setTextSize error-text 16.0)
        (.setPadding error-text 16 16 16 16)
        error-text))))

(defn create-app-detail []
  (try
    (debug-log "Creating app detail view...")
    (let [app @selected-app
          main-layout (LinearLayout. *context*)
          scroll-view (ScrollView. *context*)
          content-layout (LinearLayout. *context*)
          title (TextView. *context*)
          intents-title (TextView. *context*)
          intents-list (get-app-intents (:package-name app))
          back-button (Button. *context*)
          launch-button (Button. *context*)]
      
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setOrientation content-layout LinearLayout/VERTICAL)
      (.setPadding main-layout 16 16 16 16)
      (.setPadding content-layout 16 16 16 16)
      
      ; Set white backgrounds
      (.setBackgroundColor main-layout Color/WHITE)
      (.setBackgroundColor scroll-view Color/WHITE)
      (.setBackgroundColor content-layout Color/WHITE)
      
      ; App title
      (.setText title (:label app))
      (.setTextSize title 24.0)
      (.setTextColor title Color/BLACK)
      (.setBackgroundColor title Color/WHITE)
      (.setPadding title 0 0 0 16)
      
      ; Intents section title
      (.setText intents-title "Supported Intents:")
      (.setTextSize intents-title 18.0)
      (.setTextColor intents-title Color/BLACK)
      (.setBackgroundColor intents-title Color/WHITE)
      (.setPadding intents-title 0 16 0 8)
      
      (.addView content-layout title)
      (.addView content-layout intents-title)
      
      ; Add intent items
      (doseq [intent intents-list]
        (let [intent-text (TextView. *context*)]
          (.setText intent-text (str "• " intent))
          (.setTextColor intent-text Color/BLACK)
          (.setBackgroundColor intent-text Color/WHITE)
          (.setTextSize intent-text 14.0)
          (.setPadding intent-text 16 4 0 4)
          (.addView content-layout intent-text)))
      
      ; Back button
      (.setText back-button "← Back to List")
      (.setTextColor back-button Color/BLACK)
      (.setBackgroundColor back-button Color/LTGRAY)
      (.setTextSize back-button 16.0)
      (.setPadding back-button 16 12 16 12)
      (let [back-params (LinearLayout$LayoutParams. 
                        LinearLayout$LayoutParams/MATCH_PARENT
                        LinearLayout$LayoutParams/WRAP_CONTENT)]
        (.setMargins back-params 0 16 0 8)
        (.setLayoutParams back-button back-params))
      
      (.setOnClickListener back-button
        (proxy [View$OnClickListener] []
          (onClick [v] (back-button-handler))))
      
      ; Launch button
      (.setText launch-button (str "Launch " (:label app)))
      (.setTextColor launch-button Color/BLACK)
      (.setBackgroundColor launch-button Color/LTGRAY)
      (.setTextSize launch-button 16.0)
      (.setPadding launch-button 16 12 16 12)
      (let [launch-params (LinearLayout$LayoutParams. 
                          LinearLayout$LayoutParams/MATCH_PARENT
                          LinearLayout$LayoutParams/WRAP_CONTENT)]
        (.setMargins launch-params 0 8 0 0)
        (.setLayoutParams launch-button launch-params))
      
      (.setOnClickListener launch-button
        (proxy [View$OnClickListener] []
          (onClick [v] (launch-button-handler))))
      
      (.addView content-layout back-button)
      (.addView content-layout launch-button)
      
      (.addView scroll-view content-layout)
      (.addView main-layout scroll-view)
      
      main-layout)
    (catch Exception e
      (debug-log (str "Error creating app detail: " (.getMessage e)))
      (let [error-text (TextView. *context*)]
        (.setText error-text "Error loading app details")
        (.setTextColor error-text Color/BLACK)
        (.setBackgroundColor error-text Color/WHITE)
        (.setTextSize error-text 16.0)
        (.setPadding error-text 16 16 16 16)
        error-text))))

(defn create-list-view []
  (try
    (debug-log "Creating list view...")
    (let [main-layout (LinearLayout. *context*)
          title (TextView. *context*)
          app-list (create-app-list)]
      
      (.setOrientation main-layout LinearLayout/VERTICAL)
      (.setPadding main-layout 16 16 16 16)
      (.setBackgroundColor main-layout Color/WHITE)
      
      (.setText title "Installed Applications")
      (.setTextSize title 20.0)
      (.setTextColor title Color/BLACK)
      (.setBackgroundColor title Color/WHITE)
      (.setPadding title 0 0 0 16)
      
      (.addView main-layout title)
      (.addView main-layout app-list)
      
      main-layout)
    (catch Exception e
      (debug-log (str "Error creating list view: " (.getMessage e)))
      (let [error-text (TextView. *context*)]
        (.setText error-text "Error creating list view")
        (.setTextColor error-text Color/BLACK)
        (.setBackgroundColor error-text Color/WHITE)
        (.setTextSize error-text 16.0)
        (.setPadding error-text 16 16 16 16)
        error-text))))

(defn setup-ui []
  (try
    (debug-log (str "Setting up UI for view: " @current-view))
    (let [view (case @current-view
                 :list (create-list-view)
                 :detail (create-app-detail)
                 (create-list-view))]
      (.setContentView *context* view)
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
    (debug-log "Starting Enhanced Installed Apps Browser")
    (debug-log (str "Context: " *context*))
    (debug-log (str "Content layout: " *content-layout*))
    (debug-log (str "Cache dir: " *cache-dir*))
    
    (setup-lifecycle)
    (debug-log "App initialization complete")
    (catch Exception e
      (debug-log (str "Error in -main: " (.getMessage e))))))
