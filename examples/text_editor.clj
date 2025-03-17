;; Simple text editor with persistence
(import '[android.content Context]
        '[android.widget EditText TextView LinearLayout LinearLayout$LayoutParams Button]
        '[android.view View ViewGroup$LayoutParams Gravity View$OnAttachStateChangeListener View$OnClickListener]
        '[android.graphics Typeface Color]
        '[android.text InputType TextWatcher]
        '[android.util TypedValue]
        '[java.io File FileReader FileWriter BufferedReader BufferedWriter]
        '[android.view.inputmethod InputMethodManager]
        '[android.os Handler Looper])

;; Constants
(def file-name "saved_text.txt")
(def TAG "ClojureApp")
(def save-interval 50000) ;; 2 seconds - much more frequent auto-save

;; Track if text has changed since last save
(def text-changed (atom false))
(def last-saved-text (atom ""))

;; Get the file for saving/loading text
(defn get-file []
  (File. ^String *cache-dir* file-name))

;; Save text to a file
(defn save-text [text]
  (when (or (empty? @last-saved-text) (not= text @last-saved-text))
    (reset! last-saved-text text)
    (reset! text-changed false)
    (let [file (get-file)]
      (android.util.Log/d TAG (str "Saving to file: " (.getAbsolutePath file)))
      (try
        (with-open [writer (BufferedWriter. (FileWriter. file))]
          (.write writer text)
          (android.util.Log/d TAG (str "Text saved successfully: " (if (> (count text) 20) 
                                                                     (str (subs text 0 20) "...") 
                                                                     text))))
        (catch Exception e
          (android.util.Log/e TAG (str "Error saving text: " (.getMessage e))))))))

;; Load text from a file
(defn load-text []
  (let [file (get-file)]
    (android.util.Log/d TAG (str "Loading from file: " (.getAbsolutePath file)))
    (if (.exists file)
      (try
        (with-open [reader (BufferedReader. (FileReader. file))]
          (let [line (.readLine reader)
                sb (StringBuilder.)]
            (when line
              (.append sb line))
            (loop [line (.readLine reader)]
              (when line
                (.append sb "\n")
                (.append sb line)
                (recur (.readLine reader))))
            (let [result (.toString sb)]
              (android.util.Log/d TAG (str "Text loaded successfully: " result))
              result)))
        (catch Exception e
          (android.util.Log/e TAG (str "Error loading text: " (.getMessage e)))
          ""))
      (do
        (android.util.Log/d TAG "No saved file exists yet")
        ""))))

;; Function to create UI
(defn create-text-editor! []
  (let [context *context*
        content-layout *content-layout*

        ;; Create a vertical layout
        layout (doto (LinearLayout. context)
                 (.setOrientation LinearLayout/VERTICAL)
                 (.setPadding 32 32 32 32))

        ;; Create a title
        title (doto (TextView. context)
                (.setText "Simple Text Editor")
                (.setTextSize 24)
                (.setTypeface Typeface/DEFAULT_BOLD)
                (.setTextColor Color/BLACK)
                (.setPadding 0 0 0 32)
                (.setGravity Gravity/CENTER))

        ;; Create a subtitle/instructions
        subtitle (doto (TextView. context)
                   (.setText "Enter your text below. It will be saved automatically.")
                   (.setTextSize 16)
                   (.setTextColor Color/DKGRAY)
                   (.setPadding 0 0 0 32))

        ;; Create the EditText for user input
        edit-text (doto (EditText. context)
                    (.setHint "Type here...")
                    (.setInputType (bit-or InputType/TYPE_CLASS_TEXT
                                           InputType/TYPE_TEXT_FLAG_MULTI_LINE))
                    (.setMinLines 5)
                    (.setMaxLines 10)
                    (.setTextSize 18)
                    (.setTextColor Color/BLACK)
                    (.setHintTextColor (Color/argb 128 0 0 0))
                    (.setGravity Gravity/TOP)
                    (.setPadding 16 16 16 16)
                    (.setBackground (let [drawable (android.graphics.drawable.GradientDrawable.)]
                                      (.setStroke drawable 2 Color/BLACK)
                                      (.setColor drawable Color/WHITE)
                                      (.setCornerRadius drawable 8)
                                      drawable)))

        ;; Create a save button
        save-button (doto (Button. context)
                      (.setText "Save")
                      (.setTextColor Color/WHITE)
                      (.setBackgroundColor (Color/rgb 33 150 243)) ;; Material blue
                      (.setOnClickListener 
                       (proxy [View$OnClickListener] []
                         (onClick [v]
                           (let [text-content (str (.getText edit-text))]
                             (android.util.Log/d TAG "Save button clicked")
                             (save-text text-content))))))

        ;; Create a status text view
        status-text (doto (TextView. context)
                      (.setText "")
                      (.setTextSize 14)
                      (.setTextColor Color/DKGRAY)
                      (.setPadding 0 16 0 0))

        ;; Layout params
        edit-params (android.widget.LinearLayout$LayoutParams. 
                     android.view.ViewGroup$LayoutParams/MATCH_PARENT
                     (int (* 300 (.density (.. context getResources getDisplayMetrics)))))
        
        button-params (android.widget.LinearLayout$LayoutParams.
                       android.view.ViewGroup$LayoutParams/MATCH_PARENT
                       android.view.ViewGroup$LayoutParams/WRAP_CONTENT)
        
        full-width-params (android.widget.LinearLayout$LayoutParams.
                           android.view.ViewGroup$LayoutParams/MATCH_PARENT
                           android.view.ViewGroup$LayoutParams/WRAP_CONTENT)
        
        ;; Create a handler for auto-save
        handler (Handler. (Looper/getMainLooper))
        auto-save-runnable (proxy [java.lang.Runnable] []
                             (run []
                               (when @text-changed
                                 (let [text-content (str (.getText edit-text))]
                                   (android.util.Log/d TAG "Auto-saving text")
                                   (save-text text-content)
                                   (.setText status-text "Auto-saved")
                                   ;; Create a new Handler correctly and use it
                                   (let [status-handler (Handler. (Looper/getMainLooper))]
                                     (.postDelayed 
                                      status-handler
                                      (proxy [java.lang.Runnable] [] 
                                        (run [] (.setText status-text "")))
                                      3000)))) ;; Clear "Auto-saved" after 3 seconds
                               ;; Schedule next auto-save run
                               (.postDelayed handler this save-interval)))]

    ;; Add the views to the layout
    (.addView layout title full-width-params)
    (.addView layout subtitle full-width-params)
    (.addView layout edit-text edit-params)
    (.addView layout save-button button-params)
    (.addView layout status-text full-width-params)

    ;; Add the layout to the content layout
    (.addView content-layout layout)

    ;; Load saved text if any
    (let [saved-text (load-text)]
      (when-not (empty? saved-text)
        (.setText edit-text saved-text)
        (.setSelection edit-text (.length saved-text))
        (reset! last-saved-text saved-text)))

    ;; Add a TextWatcher to track changes but not save immediately
    (.addTextChangedListener
     edit-text
     (proxy [android.text.TextWatcher] []
       (beforeTextChanged [s start count after])
       (onTextChanged [s start before count])
       (afterTextChanged [editable]
         (reset! text-changed true))))
    
    ;; Add focus change listener - save when focus is lost
    (.setOnFocusChangeListener
     edit-text
     (proxy [android.view.View$OnFocusChangeListener] []
       (onFocusChange [view hasFocus]
         (when-not hasFocus
           (let [text-content (str (.getText edit-text))]
             (android.util.Log/d TAG (str "Focus lost - Saving text"))
             (save-text text-content))))))
    
    ;; View attachment listener - start auto-save when attached
    (.addOnAttachStateChangeListener
     layout
     (proxy [android.view.View$OnAttachStateChangeListener] []
       (onViewAttachedToWindow [v]
         (android.util.Log/d TAG "Text editor view attached - starting auto-save")
         (.postDelayed handler auto-save-runnable save-interval))
       (onViewDetachedFromWindow [v]
         (let [text-content (str (.getText edit-text))]
           (android.util.Log/d TAG (str "View detached - Saving text"))
           (save-text text-content)
           (.removeCallbacks handler auto-save-runnable)))))
    
    ;; Add a key listener to detect back button presses - fixed to always return a boolean
    (.setOnKeyListener
     edit-text
     (proxy [android.view.View$OnKeyListener] []
       (onKey [v keyCode event]
         (if (and (= keyCode android.view.KeyEvent/KEYCODE_BACK)
                  (= (.getAction event) android.view.KeyEvent/ACTION_DOWN))
           (do
             (let [text-content (str (.getText edit-text))]
               (android.util.Log/d TAG "Back button detected - Saving text")
               (save-text text-content))
             false) ;; Return false to allow normal back button behavior
           false)))) ;; Return false for all other keys

    ;; Return the edit-text for reference
    edit-text))

;; Entry point function
(defn -main []
  (create-text-editor!))
