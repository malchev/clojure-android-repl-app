package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;

public class LLMClientFactory {
    private static final String TAG = "LLMClientFactory";

    public enum LLMType {
        GEMINI,
        STUB
    }

    public static LLMClient createClient(Context context, LLMType type) {
        Log.d(TAG, "Creating LLM client of type: " + type);
        switch (type) {
            case GEMINI:
                GeminiLLMClient client = new GeminiLLMClient(context);
                // Get the selected model from the activity
                if (context instanceof ClojureAppDesignActivity) {
                    String selectedModel = ((ClojureAppDesignActivity) context).getSelectedModel();
                    if (selectedModel != null && !selectedModel.isEmpty()) {
                        client.setModel(selectedModel);
                    }
                }
                return client;
            case STUB:
            default:
                return new StubLLMClient(context);
        }
    }
}
