;; Feature test
;; Part 1: Test spec functionality
(do
  (android.util.Log/i "ClojureFeatureTest" "Starting spec test...")
  (require '[clojure.spec.alpha :as s])
  (android.util.Log/i "ClojureFeatureTest" "Spec required successfully"))

;; Define specs
(do
  (s/def ::button-config (s/keys :req-un [::text ::size ::color]))
  (s/def ::text string?)
  (s/def ::size number?)
  (s/def ::color string?)
  (android.util.Log/i "ClojureFeatureTest" "Specs defined successfully"))

;; Test valid config
(do
  (def valid-config {:text "Test Button" :size 24.0 :color "#4CAF50"})
  (let [valid? (s/valid? ::button-config valid-config)]
    (android.util.Log/i "ClojureFeatureTest" (str "Config validation result: " valid?))))

;; Part 2: Test dynamic class generation
(do
  (android.util.Log/i "ClojureFeatureTest" "Starting dynamic class test...")
  (def dynamic-button
    (proxy [android.widget.Button] [*context*]
      (onTouchEvent [event]
        (android.util.Log/i "ClojureFeatureTest" "Dynamic button touched!")
        (proxy-super onTouchEvent event))))
  (android.util.Log/i "ClojureFeatureTest" "Dynamic button class created"))

;; Configure button
(do
  (.setId dynamic-button (android.view.View/generateViewId))
  (.setVisibility dynamic-button android.view.View/VISIBLE)
  (.setText dynamic-button "Dynamic Button")
  (.setTextSize dynamic-button (:size valid-config))
  (.setBackgroundColor dynamic-button (android.graphics.Color/parseColor (:color valid-config)))
  (.setTextColor dynamic-button (android.graphics.Color/parseColor "#FFFFFF"))
  (android.util.Log/i "ClojureFeatureTest" "Button configured"))

;; Add to layout
(do
  (def params (android.widget.LinearLayout$LayoutParams.
               android.widget.LinearLayout$LayoutParams/MATCH_PARENT
               android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
  (.setMargins params 20 20 20 20)
  (.post *content-layout*
    (proxy [java.lang.Runnable] []
      (run []
        (.addView *content-layout* dynamic-button params)
        (android.util.Log/i "ClojureFeatureTest" "Dynamic button added to layout"))))
  "Test completed - check logcat for results")
