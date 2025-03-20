package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;

public class LLMClientFactory {
    private static final String TAG = "LLMClientFactory";

    public enum LLMType {
        STUB,
        GEMINI
    }

    public static LLMClient createClient(Context context, LLMType type) {
        Log.d(TAG, "Creating LLM client of type: " + type);
        switch (type) {
            case STUB:
                return new StubLLMClient(context);
            case GEMINI:
                return new GeminiLLMClient(context);
            default:
                Log.w(TAG, "Unknown LLM type: " + type + ", falling back to STUB");
                return new StubLLMClient(context);
        }
    }
}
