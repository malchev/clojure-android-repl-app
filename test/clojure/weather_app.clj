;; Weather App Implementation
(let [activity *context*
      content *content-layout*
      handler (android.os.Handler. (android.os.Looper/getMainLooper))
      tag "WeatherApp"  ;; Tag for logging
      
      ;; Create UI elements
      main-layout (doto (android.widget.LinearLayout. activity)
                   (.setOrientation android.widget.LinearLayout/VERTICAL)
                   (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT))
                   (.setPadding 32 32 32 32))
      
      details-layout (doto (android.widget.LinearLayout. activity)
                      (.setOrientation android.widget.LinearLayout/VERTICAL)
                      (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                       android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                       android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                      (.setPadding 0 16 0 16))
      
      status-text (doto (android.widget.TextView. activity)
                   (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                   (.setTextSize 16.0))
      
      temp-text (doto (android.widget.TextView. activity)
                 (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                   android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                   android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                 (.setTextSize 48.0)
                 (.setGravity android.view.Gravity/CENTER))
      
      desc-text (doto (android.widget.TextView. activity)
                 (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                   android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                   android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                 (.setTextSize 20.0)
                 (.setGravity android.view.Gravity/CENTER))
      
      weather-icon (doto (android.widget.TextView. activity)
                    (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                     android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                     android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                    (.setTextSize 48.0)
                    (.setGravity android.view.Gravity/CENTER))
      
      wind-text (doto (android.widget.TextView. activity)
                 (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                   android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                   android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                 (.setTextSize 16.0)
                 (.setGravity android.view.Gravity/CENTER))
      
      humidity-text (doto (android.widget.TextView. activity)
                     (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                      android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                      android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                     (.setTextSize 16.0)
                     (.setGravity android.view.Gravity/CENTER))
      
      forecast-scroll (doto (android.widget.HorizontalScrollView. activity)
                       (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                        android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                        android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)))
      
      forecast-container (doto (android.widget.LinearLayout. activity)
                          (.setOrientation android.widget.LinearLayout/HORIZONTAL)
                          (.setLayoutParams (android.widget.LinearLayout$LayoutParams.
                                           android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                                           android.widget.LinearLayout$LayoutParams/WRAP_CONTENT))
                          (.setPadding 16 16 16 16))
      
      refresh-btn (doto (android.widget.Button. activity)
                   (.setText "Refresh Weather")
                   (.setLayoutParams (doto (android.widget.LinearLayout$LayoutParams.
                                          android.widget.LinearLayout$LayoutParams/MATCH_PARENT
                                          android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                                     (.setMargins 0 32 0 0))))]
  
  ;; Add views to layout
  (doto main-layout
    (.addView status-text)
    (.addView weather-icon)
    (.addView temp-text)
    (.addView desc-text)
    (.addView details-layout)
    (.addView forecast-scroll)
    (.addView refresh-btn))
  
  (.addView forecast-scroll forecast-container)
  
  (doto details-layout
    (.addView wind-text)
    (.addView humidity-text))
  
  (.addView content main-layout)
  
  ;; Function to update UI on main thread
  (defn run-on-ui [f]
    (.post handler f))
  
  ;; Function to read response from URL
  (defn read-url [url-obj]
    (android.util.Log/d tag (str "Reading from URL: " (.toString url-obj)))
    (let [conn (.openConnection url-obj)
          _ (.setRequestProperty conn "User-Agent" "ClojureAndroidWeatherApp/1.0")
          stream (java.io.BufferedReader. (java.io.InputStreamReader. (.getInputStream conn)))
          content (StringBuilder.)
          buffer (char-array 1024)]
      (loop []
        (let [n (.read stream buffer)]
          (when (pos? n)
            (.append content buffer 0 n)
            (recur))))
      (let [result (.toString content)]
        (android.util.Log/d tag (str "API response: " result))
        result)))

  ;; Function to get NOAA points data
  (defn get-noaa-points [lat lon]
    (android.util.Log/d tag (str "Getting NOAA points data for lat: " lat ", lon: " lon))
    (let [url (java.net.URL. (format "https://api.weather.gov/points/%.4f,%.4f" lat lon))
          response (read-url url)
          json (org.json.JSONObject. response)]
      (.getJSONObject json "properties")))

  ;; Function to make weather request
  (defn fetch-weather [lat lon]
    (android.util.Log/d tag (str "Fetching weather for lat: " lat ", lon: " lon))
    (try
      (let [points (get-noaa-points lat lon)
            forecast-url (java.net.URL. (.getString points "forecast"))
            _ (android.util.Log/d tag (str "Forecast URL: " (.toString forecast-url)))
            forecast-response (read-url forecast-url)]
        forecast-response)
      (catch Exception e
        (android.util.Log/e tag (str "Error fetching weather: " (.getMessage e)))
        (throw e))))
  
  ;; Function to parse weather JSON
  (defn parse-weather [json-str]
    (android.util.Log/d tag "Parsing weather JSON")
    (let [json (org.json.JSONObject. json-str)
          properties (.getJSONObject json "properties")
          periods (.getJSONArray properties "periods")
          current-period (.getJSONObject periods 0)
          forecasts (for [i (range (.length periods))]
                     (let [period (.getJSONObject periods i)]
                       {:name (.getString period "name")
                        :temp (.getInt period "temperature")
                        :description (.getString period "shortForecast")
                        :detailed (.getString period "detailedForecast")
                        :wind-speed (.getString period "windSpeed")
                        :wind-direction (.getString period "windDirection")
                        :is-daytime (.getBoolean period "isDaytime")}))
          result {:current (first forecasts)
                 :forecasts (rest forecasts)}]
      (android.util.Log/d tag (str "Parsed weather data: " result))
      result))

  ;; Function to create a forecast day view
  (defn create-forecast-day [forecast]
    (let [day-container (doto (android.widget.LinearLayout. activity)
                         (.setOrientation android.widget.LinearLayout/VERTICAL)
                         (.setLayoutParams (doto (android.widget.LinearLayout$LayoutParams.
                                                android.widget.LinearLayout$LayoutParams/WRAP_CONTENT
                                                android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)
                                           (.setMargins 8 0 8 0)))
                         (.setPadding 16 16 16 16))
          name-text (doto (android.widget.TextView. activity)
                     (.setText (:name forecast))
                     (.setTextSize 14.0)
                     (.setGravity android.view.Gravity/CENTER))
          icon-text (doto (android.widget.TextView. activity)
                     (.setText (get-weather-symbol (:description forecast)))
                     (.setTextSize 24.0)
                     (.setGravity android.view.Gravity/CENTER))
          temp-text (doto (android.widget.TextView. activity)
                     (.setText (format "%d°F" (:temp forecast)))
                     (.setTextSize 14.0)
                     (.setGravity android.view.Gravity/CENTER))]
      (doto day-container
        (.addView name-text)
        (.addView icon-text)
        (.addView temp-text))))

  ;; Function to get weather symbol
  (defn get-weather-symbol [description]
    (let [desc (.toLowerCase (str description))]
      (cond
        (or (.contains desc "sunny")
            (.contains desc "clear")) "☀️"
        (.contains desc "partly") "⛅"
        (.contains desc "cloudy") "☁️"
        (.contains desc "rain") "🌧️"
        (.contains desc "showers") "🌧️"
        (.contains desc "thunderstorm") "⛈️"
        (.contains desc "snow") "🌨️"
        :else "❓")))

  ;; Function to get location name
  (defn get-location-name [lat lon]
    (try
      (let [geocoder (android.location.Geocoder. activity)
            addresses (.getFromLocation geocoder lat lon 1)]
        (if (and addresses (pos? (.size addresses)))
          (let [address (.get addresses 0)
                city (.getLocality address)
                state (.getAdminArea address)]
            (if (and city state)
              (format "%s, %s" city state)
              "Unknown Location"))
          "Unknown Location"))
      (catch Exception e
        (android.util.Log/e tag (str "Geocoding error: " (.getMessage e)))
        "Unknown Location")))

  ;; Function to update weather display
  (defn update-weather [location]
    (android.util.Log/d tag "update-weather called with location")
    (let [lat (.getLatitude location)
          lon (.getLongitude location)
          location-name (get-location-name lat lon)]
      (android.util.Log/d tag (str "Location: " location-name " (lat=" lat ", lon=" lon ")"))
      (future
        (try
          (run-on-ui #(.setText status-text "Fetching weather data..."))
          (let [weather-json (fetch-weather lat lon)
                weather-data (parse-weather weather-json)
                current (:current weather-data)
                weather-symbol (get-weather-symbol (:description current))]
            (android.util.Log/d tag "Weather data fetched and parsed")
            (run-on-ui
              (fn []
                (.setText temp-text (format "%d°F" (:temp current)))
                (.setText weather-icon weather-symbol)
                (.setText desc-text (.toUpperCase (:description current)))
                (.setText status-text (str "Weather in " location-name))
                (.setText wind-text (format "Wind: %s %s" 
                                         (:wind-speed current)
                                         (:wind-direction current)))
                (.setText humidity-text (:detailed current))
                
                ;; Update forecast view
                (.removeAllViews forecast-container)
                (doseq [forecast (filter :is-daytime (:forecasts weather-data))]
                  (.addView forecast-container (create-forecast-day forecast)))
                
                (android.util.Log/d tag "UI updated with weather data"))))
          (catch Exception e
            (android.util.Log/e tag (str "Error updating weather: " (.getMessage e)))
            (run-on-ui #(.setText status-text (str "Error: " (.getMessage e)))))))))

  ;; Location callback
  (def location-callback
    (proxy [com.google.android.gms.location.LocationCallback] []
      (onLocationResult [result]
        (android.util.Log/d tag "Location callback received")
        (if-let [location (.getLastLocation result)]
          (do
            (android.util.Log/d tag "Got location from callback")
            (update-weather location))
          (android.util.Log/w tag "No location available in callback")))))
  
  ;; Function to request location updates
  (defn request-location []
    (android.util.Log/d tag "Requesting location updates")
    (let [client (com.google.android.gms.location.LocationServices/getFusedLocationProviderClient activity)
          request (doto (com.google.android.gms.location.LocationRequest/create)
                   (.setPriority com.google.android.gms.location.LocationRequest/PRIORITY_HIGH_ACCURACY)
                   (.setInterval 10000))
          builder (com.google.android.gms.location.LocationSettingsRequest$Builder.)
          _ (.addLocationRequest builder request)
          settings-client (com.google.android.gms.location.LocationServices/getSettingsClient activity)]
      (.setText status-text "Getting location...")
      (android.util.Log/d tag "Calling requestLocationUpdates")
      (-> (.checkLocationSettings settings-client (.build builder))
          (.addOnSuccessListener
           (reify com.google.android.gms.tasks.OnSuccessListener
             (onSuccess [this result]
               (android.util.Log/d tag "Location settings satisfied")
               (-> (.requestLocationUpdates client request location-callback (android.os.Looper/getMainLooper))
                   (.addOnSuccessListener
                    (reify com.google.android.gms.tasks.OnSuccessListener
                      (onSuccess [this result]
                        (android.util.Log/d tag "Location updates successfully requested"))))
                   (.addOnFailureListener
                    (reify com.google.android.gms.tasks.OnFailureListener
                      (onFailure [this e]
                        (android.util.Log/e tag (str "Failed to request location updates: " (.getMessage e)))
                        (.setText status-text (str "Error: " (.getMessage e))))))))))
          (.addOnFailureListener
           (reify com.google.android.gms.tasks.OnFailureListener
             (onFailure [this e]
               (android.util.Log/e tag (str "Location settings check failed: " (.getMessage e)))
               (.setText status-text (str "Error: Please enable location services"))))))
      (android.util.Log/d tag "Location updates requested")))

  ;; Function to check and request permissions
  (defn check-permissions []
    (android.util.Log/d tag "Checking location permissions")
    (let [permission "android.permission.ACCESS_FINE_LOCATION"]
      (if (not= android.content.pm.PackageManager/PERMISSION_GRANTED
                (.checkSelfPermission activity permission))
        (do
          (android.util.Log/d tag "Requesting location permission")
          (androidx.core.app.ActivityCompat/requestPermissions 
           activity 
           (into-array String [permission])
           1))
        (do
          (android.util.Log/d tag "Location permission already granted")
          (request-location)))))
  
  ;; Set up refresh button click handler
  (.setOnClickListener refresh-btn
    (reify android.view.View$OnClickListener
      (onClick [this view]
        (android.util.Log/d tag "Refresh button clicked")
        (check-permissions))))
  
  ;; Initial weather check
  (android.util.Log/d tag "Starting initial weather check")
  (check-permissions)
  
  ;; Return success message
  (android.util.Log/d tag "Weather app initialized")
  "Weather app initialized") 