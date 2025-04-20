;; Launches the Gmail app on startup, using robust intent approach and proper Clojure idioms.

(import
  '[android.util Log]
  '[androidx.lifecycle LifecycleEventObserver]
  '[androidx.lifecycle Lifecycle$Event]
  '[android.widget LinearLayout TextView]
  '[android.view View]
  '[android.graphics Color]
  '[android.content Intent]
  '[android.net Uri]
  '[android.content.pm PackageManager])

(def LOGTAG "ClojureApp")

(defn logd [msg]
  (Log/d LOGTAG msg))

(defn show-message [msg]
  (let [layout (LinearLayout. *context*)]
    (.setOrientation layout LinearLayout/VERTICAL)
    (let [tv (TextView. *context*)]
      (.setText tv msg)
      (.setTextColor tv Color/BLACK)
      (.setTextSize tv 20)
      (.setBackgroundColor tv Color/WHITE)
      (.setPadding tv 40 120 40 40)
      (.addView layout tv
        (android.widget.LinearLayout$LayoutParams.
          android.widget.LinearLayout$LayoutParams/MATCH_PARENT
          android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)))
    (.setContentView *context* layout)
    layout))

(defn launch-gmail []
  (logd "Trying getLaunchIntentForPackage for com.google.android.gm")
  (let [pm (.getPackageManager *context*)
        intent1 (.getLaunchIntentForPackage pm "com.google.android.gm")]
    (if intent1
      (do
        (logd "Found Gmail package via getLaunchIntentForPackage, launching.")
        (.addFlags intent1 Intent/FLAG_ACTIVITY_NEW_TASK)
        (.startActivity *context* intent1))
      (do
        (logd "getLaunchIntentForPackage failed or returned nil. Trying ACTION_SENDTO with mailto URI and Gmail package.")
        (let [intent2 (Intent. Intent/ACTION_SENDTO)]
          (.setData intent2 (Uri/parse "mailto:"))
          (.setPackage intent2 "com.google.android.gm")
          (try
            (.addFlags intent2 Intent/FLAG_ACTIVITY_NEW_TASK)
            (.startActivity *context* intent2)
            (logd "Gmail launch intent2 sent")
            (catch Exception e
              (logd (str "Exception launching Gmail: " (.getMessage e)))
              (show-message "Could not launch Gmail. Is Gmail installed and enabled?"))))))))

(defn lifecycle-handler [source event]
  (logd (str "Lifecycle callback: " (.name event)))
  (when (= (.name event) "ON_CREATE")
    (show-message "Launching Gmail...")
    (launch-gmail)))

(defn -main []
  (logd "App started, entering -main")
  (let [lifecycle (.. *context* (getLifecycle))]
    (logd (str "Current lifecycle state: " (.toString (.getCurrentState lifecycle))))
    (let [observer
          (proxy [LifecycleEventObserver] []
            (onStateChanged [source event]
              (lifecycle-handler source event)))]
      (try
        (.addObserver lifecycle observer)
        (logd "Lifecycle observer registered.")
        (catch Exception e
          (logd (str "Error registering lifecycle observer: " (.getMessage e))))))))