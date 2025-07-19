package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import com.example.clojurerepl.auth.ApiKeyManager;
import java.util.List;
import java.util.Arrays;
import java.util.UUID;

public class LLMClientFactory {
    private static final String TAG = "LLMClientFactory";

    public enum LLMType {
        GEMINI,
        OPENAI,
        CLAUDE,
        STUB
    }

    public static LLMClient createClient(Context context, LLMType type, String modelName,
            LLMClient.ChatSession chatSession) {
        Log.d(TAG, "Creating LLM client of type: " + type + " for session: " + chatSession.getSessionId());

        switch (type) {
            case GEMINI:
                GeminiLLMClient geminiClient = new GeminiLLMClient(context, chatSession);
                if (modelName != null) {
                    geminiClient.setModel(modelName);
                }
                return geminiClient;
            case OPENAI:
                OpenAIChatClient openaiClient = new OpenAIChatClient(context, chatSession);
                if (modelName != null) {
                    openaiClient.setModel(modelName);
                }
                return openaiClient;
            case CLAUDE:
                ClaudeLLMClient claudeClient = new ClaudeLLMClient(context, chatSession);
                if (modelName != null) {
                    claudeClient.setModel(modelName);
                }
                return claudeClient;
            case STUB:
                return new StubLLMClient(context, chatSession);
            default:
                throw new IllegalArgumentException("Unknown LLM type: " + type);
        }
    }

    public static List<String> getAvailableModels(Context context, LLMType type) {
        Log.d(TAG, "Getting available models for type: " + type);
        switch (type) {
            case GEMINI:
                return GeminiLLMClient.fetchAvailableModels(context);
            case OPENAI:
                return OpenAIChatClient.fetchAvailableModels(context);
            case CLAUDE:
                return ClaudeLLMClient.fetchAvailableModels(context);
            case STUB:
                return Arrays.asList("stub-model");
            default:
                throw new IllegalArgumentException("Unknown LLM type: " + type);
        }
    }

    /**
     * Clears the model cache for all LLM types
     * This is useful when API keys are changed, as different keys might have access
     * to different models
     */
    public static void clearAllModelCaches() {
        Log.d(TAG, "Clearing all model caches");
        GeminiLLMClient.clearModelCache();
        OpenAIChatClient.clearModelCache();
        ClaudeLLMClient.clearModelCache();
    }
}
