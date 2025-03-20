package com.example.clojurerepl.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

public class ApiKeyManager {
    private static final String TAG = "ApiKeyManager";
    private static final String PREF_NAME = "api_key_prefs";
    private static final String KEY_API_KEY = "gemini_api_key";

    private final Context context;

    // Singleton pattern
    private static ApiKeyManager instance;

    public static synchronized ApiKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new ApiKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    private ApiKeyManager(Context context) {
        this.context = context;
    }

    public boolean hasApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String apiKey = prefs.getString(KEY_API_KEY, null);
        return apiKey != null && !apiKey.isEmpty();
    }

    public String getApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_API_KEY, null);
    }

    public void saveApiKey(String apiKey) {
        if (apiKey == null || apiKey.trim().isEmpty()) {
            Log.e(TAG, "Attempted to save empty API key");
            return;
        }

        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.putString(KEY_API_KEY, apiKey.trim());
        editor.apply();

        Log.d(TAG, "API key saved successfully");
    }

    public void clearApiKey() {
        SharedPreferences.Editor editor = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE).edit();
        editor.remove(KEY_API_KEY);
        editor.apply();
        Log.d(TAG, "API key cleared");
    }
}
