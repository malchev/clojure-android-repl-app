;; Create a vertical layout with multiple buttons
(let [layout-params (doto (new android.widget.LinearLayout$LayoutParams
                            android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                            android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                      (.setMargins 20 20 20 20))
      button-params (doto (new android.widget.LinearLayout$LayoutParams
                            android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                            android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                      (.setMargins 10 10 10 10))
      layout (doto (android.widget.LinearLayout. *context*)
               (.setOrientation android.widget.LinearLayout/VERTICAL)
               (.setLayoutParams layout-params))]
  
  ;; Add the vertical layout to the content layout
  (.addView *content-layout* layout)
  
  ;; Add some buttons with click handlers
  (doseq [i (range 3)]
    (let [btn (doto (android.widget.Button. *context*)
                (.setText (str "Button " i))
                (.setTextSize 20.0)
                (.setPadding 20 20 20 20)
                (.setBackgroundColor (unchecked-int 0xFF4CAF50))
                (.setTextColor (unchecked-int 0xFFFFFFFF))
                (.setLayoutParams button-params))]
      (.setOnClickListener btn
        (reify android.view.View$OnClickListener
          (onClick [this v]
            (.setText btn (str "Clicked " i "!")))))
      (.addView layout btn)))
  
  ;; Return a success message
  "Created vertical layout with buttons")
