;; Button test
(defn -main []
  (let [context *context*
        button (doto (new android.widget.Button context)
                (.setText "Click me!")
                (.setTextSize 24.0)
                (.setPadding 20 20 20 20)
                (.setBackgroundColor (unchecked-int 0xFF4CAF50))
                (.setTextColor (unchecked-int 0xFFFFFFFF)))
        params (doto (new android.widget.LinearLayout$LayoutParams
                        android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                        android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                  (.setMargins 20 20 20 20))]
    (.setOnClickListener button
      (reify android.view.View$OnClickListener
        (onClick [this view]
          (.setText button "Clicked!"))))
    (.addView *content-layout* button params)
    "Button created"))
