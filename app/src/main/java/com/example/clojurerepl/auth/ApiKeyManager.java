package com.example.clojurerepl.auth;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;
import com.example.clojurerepl.LLMClientFactory;

public class ApiKeyManager {
    private static final String TAG = "ApiKeyManager";
    private static final String PREFS_NAME = "ApiKeyPrefs";
    private static final String GEMINI_API_KEY = "gemini_api_key";
    private static final String OPENAI_API_KEY = "openai_api_key";
    private static ApiKeyManager instance;
    private final SharedPreferences prefs;

    private ApiKeyManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static synchronized ApiKeyManager getInstance(Context context) {
        if (instance == null) {
            instance = new ApiKeyManager(context.getApplicationContext());
        }
        return instance;
    }

    public void saveApiKey(String apiKey) {
        saveApiKey(apiKey, LLMClientFactory.LLMType.GEMINI);
    }

    public void saveApiKey(String apiKey, LLMClientFactory.LLMType type) {
        Log.d(TAG, "Saving API key for type: " + type);
        SharedPreferences.Editor editor = prefs.edit();
        switch (type) {
            case GEMINI:
                editor.putString(GEMINI_API_KEY, apiKey);
                break;
            case OPENAI:
                editor.putString(OPENAI_API_KEY, apiKey);
                break;
            default:
                Log.w(TAG, "Unknown API key type: " + type);
                return;
        }
        editor.apply();
    }

    public String getApiKey() {
        return getApiKey(LLMClientFactory.LLMType.GEMINI);
    }

    public String getApiKey(LLMClientFactory.LLMType type) {
        Log.d(TAG, "Getting API key for type: " + type);
        switch (type) {
            case GEMINI:
                return prefs.getString(GEMINI_API_KEY, null);
            case OPENAI:
                return prefs.getString(OPENAI_API_KEY, null);
            default:
                Log.w(TAG, "Unknown API key type: " + type);
                return null;
        }
    }

    public boolean hasApiKey() {
        return hasApiKey(LLMClientFactory.LLMType.GEMINI);
    }

    public boolean hasApiKey(LLMClientFactory.LLMType type) {
        String key = getApiKey(type);
        return key != null && !key.isEmpty();
    }

    public void clearApiKey() {
        SharedPreferences.Editor editor = prefs.edit();
        editor.remove(GEMINI_API_KEY);
        editor.remove(OPENAI_API_KEY);
        editor.apply();
        Log.d(TAG, "API key cleared");
    }

    /**
     * Clears the stored API key for the specified LLM type
     *
     * @param type The LLM type to clear the key for
     */
    public void clearApiKey(LLMClientFactory.LLMType type) {
        Log.d(TAG, "Clearing API key for: " + type);
        if (type == LLMClientFactory.LLMType.OPENAI) {
            prefs.edit().remove(OPENAI_API_KEY).apply();
        } else {
            // Default to Gemini
            prefs.edit().remove(GEMINI_API_KEY).apply();
        }
    }
}
