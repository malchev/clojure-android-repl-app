;; Displays the Chinese character for wisdom (慧) in a red, rounded square.
(def TAG "ClojureApp")

(import
  'android.util.Log
  'android.util.DisplayMetrics
  'android.util.TypedValue
  'android.view.Gravity
  'android.widget.TextView
  'android.widget.LinearLayout
  'android.widget.FrameLayout
  'android.widget.LinearLayout$LayoutParams
  'android.graphics.Color
  'android.graphics.drawable.GradientDrawable
  'androidx.lifecycle.LifecycleEventObserver
  'androidx.lifecycle.Lifecycle$Event)

(defn log-debug
  "Helper function for logging debug messages."
  [msg]
  (Log/d TAG (str msg)))

(defn handle-lifecycle-change
  "Handles Android lifecycle events."
  [source event]
  (log-debug (str "Lifecycle event: " (.name event) " for source: " source)))

(defn -main []
  (try
    (log-debug "App starting...")
    (.removeAllViews *content-layout*)

    ;; Set up lifecycle observation
    (let [lifecycle (.. *context* getLifecycle)
          observer (proxy [LifecycleEventObserver] []
                     (onStateChanged [source event]
                       (handle-lifecycle-change source event)))]
      (.addObserver lifecycle observer)
      (log-debug (str "Lifecycle observer added. Current state: " (.. lifecycle getCurrentState name))))

    ;; Configure the main layout
    (.setGravity *content-layout* Gravity/CENTER)
    (.setBackgroundColor *content-layout* Color/WHITE)

    ;; Create the UI elements
    (let [display-metrics (new DisplayMetrics)
          _ (.. *context* getWindowManager getDefaultDisplay (getMetrics display-metrics))
          screen-width (.-widthPixels display-metrics)
          ;; Final size increase for maximum impact
          square-size (int (* screen-width 0.85))
          corner-radius 160.0

          ;; 1. Create the red rounded-corner drawable for the background
          shape-drawable (doto (new GradientDrawable)
                           (.setShape GradientDrawable/RECTANGLE)
                           (.setColor Color/RED)
                           (.setCornerRadius corner-radius))

          ;; 2. Create the TextView for the character
          wisdom-char-view (doto (new TextView *context*)
                             (.setText "慧")
                             (.setTextColor Color/WHITE)
                             (.setGravity Gravity/CENTER)
                             (.setIncludeFontPadding false)
                             ;; Key change: Use custom auto-sizing for aggressive scaling.
                             ;; This provides fine-grained control to maximize the text size.
                             (.setAutoSizeTextTypeUniformWithConfiguration
                               12    ; autoSizeMinTextSize in SP
                               500   ; autoSizeMaxTextSize in SP (a high value)
                               1     ; autoSizeStepGranularity in PX (very fine steps)
                               TypedValue/COMPLEX_UNIT_SP)) ; Unit for min/max sizes

          ;; 3. Create a FrameLayout to hold the background and the text
          frame-layout (doto (new FrameLayout *context*)
                         (.setBackground shape-drawable)
                         (.setLayoutParams (new LinearLayout$LayoutParams
                                                square-size
                                                square-size))
                         (.addView wisdom-char-view))]

      ;; Add the final composed view to the main content layout
      (.addView *content-layout* frame-layout)
      (log-debug (str "UI created with custom auto-sizing configuration.")))

    (catch Exception e
      (Log/e TAG "An error occurred in -main" e))))
