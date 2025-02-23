;; Part 1: Test spec functionality
(do
  (android.util.Log/i "ClojureREPL" "Starting spec test...")
  (require '[clojure.spec.alpha :as s])
  (android.util.Log/i "ClojureREPL" "Spec required successfully"))

;; Define specs
(do
  (s/def ::button-config (s/keys :req-un [::text ::size ::color]))
  (s/def ::text string?)
  (s/def ::size number?)
  (s/def ::color string?)
  (android.util.Log/i "ClojureREPL" "Specs defined successfully"))

;; Test valid config
(do
  (def valid-config {:text "Test Button" :size 24.0 :color "#4CAF50"})
  (let [valid? (s/valid? ::button-config valid-config)]
    (android.util.Log/i "ClojureREPL" (str "Config validation result: " valid?))))

;; Part 2: Test dynamic class generation
(do
  (android.util.Log/i "ClojureREPL" "Starting dynamic class test...")
  (def dynamic-button
    (proxy [android.widget.Button] [*activity*]
      (onTouchEvent [event]
        (android.util.Log/i "ClojureREPL" "Dynamic button touched!")
        (proxy-super onTouchEvent event))))
  (android.util.Log/i "ClojureREPL" "Dynamic button class created"))

;; Configure button
(do
  (.setId dynamic-button (android.view.View/generateViewId))
  (.setVisibility dynamic-button android.view.View/VISIBLE)
  (.setText dynamic-button "Dynamic Button")
  (.setTextSize dynamic-button (:size valid-config))
  (.setBackgroundColor dynamic-button (android.graphics.Color/parseColor (:color valid-config)))
  (.setTextColor dynamic-button (android.graphics.Color/parseColor "#FFFFFF"))
  (android.util.Log/i "ClojureREPL" "Button configured"))

;; Add to layout
(do
  (def params (android.widget.LinearLayout$LayoutParams.
               android.widget.LinearLayout$LayoutParams/MATCH_PARENT
               android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
  (.setMargins params 20 20 20 20)
  (.post *root-layout*
    (proxy [java.lang.Runnable] []
      (run []
        (.addView *root-layout* dynamic-button params)
        (android.util.Log/i "ClojureREPL" "Dynamic button added to layout"))))
  "Test completed - check logcat for results") 