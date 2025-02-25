;; Strava Visualization App Implementation
(let [activity *context*
      content *content-layout*
      handler (android.os.Handler. (android.os.Looper/getMainLooper))
      tag "StravaViz"  ;; Tag for logging
      
      _ (android.util.Log/i tag "Initializing StravaViz app")
      
      ;; Create UI elements
      main-layout (doto (android.widget.LinearLayout. activity)
                   (.setOrientation android.widget.LinearLayout/VERTICAL)
                   (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT))
                   (.setPadding 32 32 32 32))
      
      title-text (doto (android.widget.TextView. activity)
                  (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                    android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                    android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                  (.setText "Strava Running Progress")
                  (.setTextSize 24.0)
                  (.setGravity android.view.Gravity/CENTER))
      
      status-text (doto (android.widget.TextView. activity)
                   (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                   (.setTextSize 16.0))
      
      token-input (doto (android.widget.EditText. activity)
                   (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                   (.setHint "Enter your Strava access token")
                   (.setSingleLine true)
                   (.setVisibility android.view.View/GONE))
      
      chart-container (doto (android.widget.FrameLayout. activity)
                       (.setLayoutParams (doto (android.widget.LinearLayout$LayoutParams.
                                              android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                              android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                                         (.setMargins 0 32 0 32)))
                       (.setMinimumHeight 400))
      
      stats-layout (doto (android.widget.LinearLayout. activity)
                    (.setOrientation android.widget.LinearLayout/VERTICAL)
                    (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                    (.setPadding 0 16 0 16))
      
      total-distance-text (doto (android.widget.TextView. activity)
                           (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                            android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                            android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                           (.setTextSize 16.0))
      
      avg-pace-text (doto (android.widget.TextView. activity)
                     (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                      android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                      android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                     (.setTextSize 16.0))
      
      auth-button (doto (android.widget.Button. activity)
                   (.setText "Connect with Strava")
                   (.setLayoutParams (doto (android.widget.LinearLayout$LayoutParams.
                                          android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                          android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                                     (.setMargins 0 32 0 0))))
      
      submit-token-button (doto (android.widget.Button. activity)
                           (.setText "Submit Token")
                           (.setLayoutParams (doto (android.widget.LinearLayout$LayoutParams.
                                                  android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                                  android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                                             (.setMargins 0 16 0 0)))
                           (.setVisibility android.view.View/GONE))
      
      ;; Custom chart view
      chart-view (proxy [android.view.View] [activity]
                  (onDraw [canvas]
                    (proxy-super onDraw canvas)
                    (when-let [data (.getTag this)]
                      (let [width (.getWidth this)
                            height (.getHeight this)
                            padding-left 200  ;; Extra space for Y-axis labels
                            padding-right 100
                            padding-top 100
                            padding-bottom 100
                            effective-width (- width (+ padding-left padding-right))
                            effective-height (- height (+ padding-top padding-bottom))
                            activities (:activities data)
                            ;; Sort activities by date in chronological order
                            parse-date #(.parse (java.text.SimpleDateFormat. "yyyy-MM-dd'T'HH:mm:ss'Z'") %)
                            sorted-activities (sort-by #(.getTime (parse-date (:start-date %))) activities)
                            dates (map :start-date sorted-activities)
                            paces (map #(/ (:moving-time %) (:distance %)) activities)
                            max-pace (apply max paces)
                            min-pace (apply min paces)
                            pace-range (- max-pace min-pace)
                            ;; Calculate date range
                            date-timestamps (map #(.getTime (parse-date %)) dates)
                            start-date (java.util.Date. (apply min date-timestamps))
                            end-date (java.util.Date. (apply max date-timestamps))
                            ms-between (- (.getTime end-date) (.getTime start-date))
                            days-between (quot ms-between (* 24 60 60 1000))
                            weeks-between (quot days-between 7)
                            paint (doto (android.graphics.Paint.)
                                   (.setColor android.graphics.Color/BLUE)
                                   (.setStrokeWidth 4)
                                   (.setStyle android.graphics.Paint$Style/STROKE)
                                   (.setAntiAlias true))
                            text-paint (doto (android.graphics.Paint.)
                                       (.setColor android.graphics.Color/WHITE)
                                       (.setTextSize 30)
                                       (.setAntiAlias true))
                            title-paint (doto (android.graphics.Paint.)
                                         (.setColor android.graphics.Color/WHITE)
                                         (.setTextSize 40)
                                         (.setAntiAlias true))
                            axis-paint (doto (android.graphics.Paint.)
                                       (.setColor android.graphics.Color/WHITE)
                                       (.setStrokeWidth 2)
                                       (.setStyle android.graphics.Paint$Style/STROKE)
                                       (.setAntiAlias true))
                            path (android.graphics.Path.)]
                        ;; Draw axes
                        (.drawLine canvas 
                                 padding-left padding-top 
                                 padding-left (- height padding-bottom)
                                 axis-paint)
                        (.drawLine canvas
                                 padding-left (- height padding-bottom)
                                 (- width padding-right) (- height padding-bottom)
                                 axis-paint)
                        
                        ;; Draw Y-axis notches (every 10 seconds)
                        (.setTextAlign text-paint android.graphics.Paint$Align/RIGHT)
                        (let [notch-size 20
                              pace-step 10  ;; 10 seconds per km
                              min-pace-mins (/ min-pace 60)
                              max-pace-mins (/ max-pace 60)
                              min-pace-rounded (* (quot (* min-pace-mins 60) pace-step) pace-step)  ;; Round down to nearest 10s
                              max-pace-rounded (* (+ (quot (* max-pace-mins 60) pace-step) 1) pace-step)  ;; Round up to nearest 10s
                              pace-range-secs (- max-pace-rounded min-pace-rounded)]
                          (doseq [pace-secs (range min-pace-rounded max-pace-rounded pace-step)]
                            (let [y (+ padding-top (* (- 1.0 (/ (- pace-secs min-pace-rounded) pace-range-secs))
                                                 effective-height))]
                              (.drawLine canvas
                                       (- padding-left notch-size) y
                                       padding-left y
                                       axis-paint)
                              (.drawText canvas
                                       (format "%.1f" (/ pace-secs 60.0))
                                       (- padding-left (+ notch-size 5))
                                       (+ y 10)
                                       text-paint))))
                        
                        ;; Draw X-axis notches (per week)
                        (.setTextAlign text-paint android.graphics.Paint$Align/CENTER)
                        (let [notch-size 20
                              weeks-step (max 1 (quot weeks-between 10))]  ;; Show at most 10 notches
                          (doseq [week (range 0 weeks-between weeks-step)]
                            (let [x (+ padding-left (* (/ week weeks-between) effective-width))]
                              (.drawLine canvas
                                       x (- height padding-bottom)
                                       x (- height (- padding-bottom notch-size))
                                       axis-paint)
                              (.drawText canvas
                                       (str week)
                                       x
                                       (- height (- padding-bottom (+ notch-size 25)))
                                       text-paint))))
                        
                        ;; Draw axis titles
                        (.setTextAlign title-paint android.graphics.Paint$Align/CENTER)
                        (.drawText canvas "Time (w)" 
                                 (/ width 2)
                                 (- height (/ padding-bottom 2))
                                 title-paint)
                        
                        ;; Draw Y-axis title (rotated)
                        (.save canvas)
                        (.rotate canvas -90 (/ padding-left 3) (/ height 2))
                        (.drawText canvas "Pace (min/km)"
                                 (/ height 2)
                                 0
                                 title-paint)
                        (.restore canvas)
                        
                        ;; Draw data points and connect them
                        (let [point-count (count activities)]
                          (doseq [[idx activity] (map-indexed vector sorted-activities)]
                            (let [x (+ padding-left (* (/ idx (dec point-count)) effective-width))
                                  pace (/ (:moving-time activity) (:distance activity))
                                  y (+ padding-top (* (- 1.0 (/ (- pace min-pace) pace-range))
                                                effective-height))]
                              (if (zero? idx)
                                (.moveTo path x y)
                                (.lineTo path x y))
                              
                              ;; Draw point
                              (.setStyle paint android.graphics.Paint$Style/FILL)
                              (.drawCircle canvas x y 8 paint)
                              (.setStyle paint android.graphics.Paint$Style/STROKE))))
                        
                        ;; Draw the path
                        (.drawPath canvas path paint)
                        
                        ;; Draw labels
                        (.setTextAlign text-paint android.graphics.Paint$Align/RIGHT)
                        (.drawText canvas 
                                 (format "%.1f min/km" (/ max-pace 60.0))
                                 (- padding-left 10)
                                 (+ padding-left 10)
                                 text-paint)
                        (.drawText canvas 
                                 (format "%.1f min/km" (/ min-pace 60.0))
                                 (- padding-left 10)
                                 (- height padding-bottom)
                                 text-paint)))))]
  
  ;; Add views to layout - IMPORTANT: Add directly to content, not to an intermediate layout
  (doto content
    (.addView title-text)
    (.addView status-text)
    (.addView auth-button)
    (.addView token-input)
    (.addView submit-token-button)
    (.addView chart-container)
    (.addView stats-layout))
  
  (doto stats-layout
    (.addView total-distance-text)
    (.addView avg-pace-text))
  
  (.addView chart-container chart-view)
  
  ;; Function to update UI on main thread
  (defn run-on-ui [f]
    (.post handler f))
  
  ;; Strava API configuration
  (def client-id "125948")  ;; Replace with your Strava API client ID
  (def client-secret "0405ee700df251cb9b1a6c82ce2c5b885069a10a")  ;; Replace with your Strava API client secret
  
  ;; Function to read response from URL
  (defn read-url [url-obj headers]
    (android.util.Log/i tag (str "Starting URL request to: " (.toString url-obj)))
    (android.util.Log/i tag (str "Request headers: " headers))
    (try
      (let [conn (doto (.openConnection url-obj)
                   (.setRequestMethod "GET"))]
        ;; Add headers
        (doseq [[k v] headers]
          (.addRequestProperty conn k v))
        
        ;; Make the request
        (.connect conn)
        
        ;; Read response
        (let [response-code (.getResponseCode conn)]
          (android.util.Log/i tag (str "Response code: " response-code))
          (if (< response-code 400)
            ;; Success response
            (let [stream (.getInputStream conn)
                  reader (java.io.BufferedReader. (java.io.InputStreamReader. stream))
                  content (StringBuilder.)
                  buffer (char-array 1024)]
              (loop []
                (let [n (.read reader buffer)]
                  (when (pos? n)
                    (.append content buffer 0 n)
                    (recur))))
              (.close reader)
              (let [result (.toString content)]
                (android.util.Log/i tag (str "Response received, length: " (.length result)))
                (android.util.Log/i tag (str "Response content: " 
                                           (if (> (.length result) 500)
                                             (str (.substring result 0 500) "...")
                                             result)))
                result))
            ;; Error response
            (let [error-stream (.getErrorStream conn)
                  reader (java.io.BufferedReader. (java.io.InputStreamReader. error-stream))
                  error-content (StringBuilder.)]
              (loop []
                (let [line (.readLine reader)]
                  (when line
                    (.append error-content line)
                    (.append error-content "\n")
                    (recur))))
              (.close reader)
              (throw (Exception. (str "HTTP " response-code ": " (.toString error-content))))))))
      (catch Exception e
        (android.util.Log/e tag (str "Error in read-url: " (.getMessage e)))
        (throw e))))

  ;; Function to post to URL
  (defn post-url [url-obj params headers]
    (android.util.Log/i tag (str "Starting POST request to: " (.toString url-obj)))
    (android.util.Log/i tag (str "POST parameters: " params))
    (android.util.Log/i tag (str "POST headers: " headers))
    (try
      (let [conn (doto (.openConnection url-obj)
                   (.setRequestMethod "POST")
                   (.setDoOutput true))]
        ;; Add headers
        (doseq [[k v] headers]
          (.addRequestProperty conn k v))
        
        ;; Write POST data
        (let [param-str (clojure.string/join "&" 
                         (for [[k v] params]
                           (str (java.net.URLEncoder/encode (str k) "UTF-8")
                                "="
                                (java.net.URLEncoder/encode (str v) "UTF-8"))))]
          (android.util.Log/i tag (str "Sending POST data: " param-str))
          (with-open [os (.getOutputStream conn)
                     writer (java.io.OutputStreamWriter. os)]
            (.write writer param-str)
            (.flush writer)))
        
        ;; Make the request
        (.connect conn)
        
        ;; Read response
        (let [response-code (.getResponseCode conn)]
          (android.util.Log/i tag (str "Response code: " response-code))
          (if (< response-code 400)
            ;; Success response
            (let [stream (.getInputStream conn)
                  reader (java.io.BufferedReader. (java.io.InputStreamReader. stream))
                  content (StringBuilder.)]
              (loop []
                (let [line (.readLine reader)]
                  (when line
                    (.append content line)
                    (.append content "\n")
                    (recur))))
              (.close reader)
              (let [result (.toString content)]
                (android.util.Log/i tag (str "Response received, length: " (.length result)))
                (android.util.Log/i tag (str "Response content: " 
                                           (if (> (.length result) 500)
                                             (str (.substring result 0 500) "...")
                                             result)))
                result))
            ;; Error response
            (let [error-stream (.getErrorStream conn)
                  reader (java.io.BufferedReader. (java.io.InputStreamReader. error-stream))
                  error-content (StringBuilder.)]
              (loop []
                (let [line (.readLine reader)]
                  (when line
                    (.append error-content line)
                    (.append error-content "\n")
                    (recur))))
              (.close reader)
              (throw (Exception. (str "HTTP " response-code ": " (.toString error-content))))))))
      (catch Exception e
        (android.util.Log/e tag (str "Error in post-url: " (.getMessage e)))
        (throw e))))

  ;; Function to exchange authorization code for token
  (defn exchange-token [code]
    (android.util.Log/i tag (str "Exchanging authorization code for token. Code: " code))
    (let [token-url (java.net.URL. "https://www.strava.com/oauth/token")
          params {"client_id" client-id
                 "client_secret" client-secret
                 "code" code
                 "grant_type" "authorization_code"}
          headers {"Content-Type" "application/x-www-form-urlencoded"}
          _ (android.util.Log/i tag "Sending token exchange request...")
          response (post-url token-url params headers)
          _ (android.util.Log/i tag "Token response received, parsing JSON...")
          json (org.json.JSONObject. response)
          result {:access-token (.getString json "access_token")
                 :refresh-token (.getString json "refresh_token")
                 :expires-at (.getLong json "expires_at")}]
      (android.util.Log/i tag (str "Token exchange successful. Access token: " 
                                  (.substring (:access-token result) 0 10) "..."))
      result))

  ;; Function to fetch activities from Strava
  (defn fetch-activities [access-token]
    (android.util.Log/i tag "Starting to fetch Strava activities")
    (let [headers {"Authorization" (str "Bearer " access-token)}
          page-size 200  ;; Maximum allowed by Strava API
          ;; Function to fetch a single page
          fetch-page (fn [page]
                      (let [activities-url (java.net.URL. 
                                          (str "https://www.strava.com/api/v3/athlete/activities"
                                               "?per_page=" page-size
                                               "&page=" page))
                            _ (android.util.Log/i tag (str "Fetching page " page "..."))
                            response (read-url activities-url headers)
                            json-array (new org.json.JSONArray (str response))]
                        (when (pos? (.length json-array))
                          json-array)))
          ;; Fetch pages until we get an empty response
          all-activities (loop [page 1
                              activities []]
                         (if-let [json-array (fetch-page page)]
                           (let [new-activities (for [i (range (.length json-array))]
                                                (let [activity (.getJSONObject json-array i)]
                                                  {:type (.getString activity "type")
                                                   :start-date (.getString activity "start_date")
                                                   :distance (.getDouble activity "distance")
                                                   :moving-time (.getInt activity "moving_time")
                                                   :average-speed (.getDouble activity "average_speed")}))
                                                 all-activities (concat activities new-activities)]
                               (if (< (.length json-array) page-size)
                                 all-activities  ;; No more pages
                                 (recur (inc page) all-activities)))
                           activities))
          running-activities (filter #(= (:type %) "Run") all-activities)]
      (android.util.Log/i tag (str "Fetched " (count all-activities) " total activities"))
      (android.util.Log/i tag (str "Filtered to " (count running-activities) " running activities"))
      running-activities))

  ;; Function to update the visualization
  (defn update-visualization [activities]
    (android.util.Log/i tag (str "Updating visualization with " (count activities) " activities"))
    (run-on-ui
     (fn []
       (let [total-distance (reduce + 0 (map :distance activities))
             total-time (reduce + 0 (map :moving-time activities))
             avg-pace (if (> total-distance 0)
                       (/ total-time total-distance)
                       0)]
         (android.util.Log/i tag (str "Total distance: " (/ total-distance 1000.0) "km"))
         (android.util.Log/i tag (str "Average pace: " (/ avg-pace 60.0) " min/km"))
         (.setTag chart-view {:activities activities})
         (.invalidate chart-view)
         (.setText total-distance-text
                  (format "Total Distance: %.2f km" (/ total-distance 1000.0)))
         (.setText avg-pace-text
                  (format "Average Pace: %.2f min/km" (/ avg-pace 60.0)))))))

  ;; Function to handle Strava authentication
  (defn handle-strava-auth []
    (android.util.Log/i tag "Setting up Strava auth button click handler")
    (.setOnClickListener auth-button
      (reify android.view.View$OnClickListener
        (onClick [this view]
          (let [redirect-uri "http://localhost"  ;; Simple redirect that Strava accepts
                auth-url (str "https://www.strava.com/oauth/authorize"
                              "?client_id=" client-id
                              "&redirect_uri=" (java.net.URLEncoder/encode redirect-uri "UTF-8")
                              "&response_type=code"
                              "&scope=activity:read_all")]
            (android.util.Log/i tag (str "Opening Strava auth URL: " auth-url))
            ;; Open browser for Strava authentication
            (.startActivity activity
              (android.content.Intent. android.content.Intent/ACTION_VIEW
                                    (android.net.Uri/parse auth-url)))
            (.setVisibility token-input android.view.View/VISIBLE)
            (.setVisibility submit-token-button android.view.View/VISIBLE)
            (.setText status-text "After authorizing, copy the 'code' parameter from the URL and paste it here."))))))
  
  ;; Function to handle token submission
  (defn handle-token-submit []
    (.setOnClickListener submit-token-button
      (reify android.view.View$OnClickListener
        (onClick [this view]
          (let [input-text (.toString (.getText token-input))
                ;; Extract code from various possible inputs:
                ;; 1. Full URL: http://localhost?code=abc...
                ;; 2. Partial URL: localhost?code=abc...
                ;; 3. Just the code: abc...
                code (or (second (re-find #"[?&]code=([^&]+)" input-text))
                        input-text)]
            (android.util.Log/i tag (str "Processing authorization code (length: " (count code) ")"))
            (.setVisibility token-input android.view.View/GONE)
            (.setVisibility submit-token-button android.view.View/GONE)
            (.setVisibility auth-button android.view.View/GONE)
            (.setText status-text "Exchanging code for access token...")
            (future
              (try
                (let [{:keys [access-token]} (exchange-token code)
                      _ (android.util.Log/i tag "Token exchange successful, fetching activities...")
                      activities (fetch-activities access-token)]
                  (update-visualization activities)
                  (run-on-ui #(.setText status-text 
                                      (format "Loaded %d activities" (count activities)))))
                (catch Exception e
                  (android.util.Log/e tag (str "Error: " (.getMessage e)))
                  (android.util.Log/e tag (str "Stack trace: " (.getStackTrace e)))
                  (run-on-ui #(do
                               (.setText status-text 
                                       (str "Error: " (.getMessage e) ". Please try again."))
                               (.setVisibility auth-button android.view.View/VISIBLE)
                               (.setVisibility token-input android.view.View/VISIBLE)
                               (.setVisibility submit-token-button android.view.View/VISIBLE)))))))))))
  
  ;; Initialize the app
  (handle-strava-auth)
  (handle-token-submit)
  
  (.setText status-text "Please connect your Strava account to begin")) 