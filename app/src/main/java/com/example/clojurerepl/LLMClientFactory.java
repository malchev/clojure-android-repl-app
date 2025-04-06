package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.util.List;
import java.util.Arrays;

public class LLMClientFactory {
    private static final String TAG = "LLMClientFactory";

    public enum LLMType {
        GEMINI,
        OPENAI,
        STUB
    }

    public static LLMClient createClient(Context context, LLMType type, String modelName) {
        Log.d(TAG, "Creating LLM client of type: " + type);
        switch (type) {
            case GEMINI:
                GeminiLLMClient geminiClient = new GeminiLLMClient(context);
                if (modelName != null) {
                    geminiClient.setModel(modelName);
                }
                return geminiClient;
            case OPENAI:
                OpenAIChatClient openaiClient = new OpenAIChatClient(context);
                if (modelName != null) {
                    openaiClient.setModel(modelName);
                }
                return openaiClient;
            case STUB:
                return new StubLLMClient(context);
            default:
                throw new IllegalArgumentException("Unknown LLM type: " + type);
        }
    }

    public static LLMClient createClient(Context context, LLMType type) {
        return createClient(context, type, null);
    }

    public static List<String> getAvailableModels(Context context, LLMType type) {
        Log.d(TAG, "Getting available models for type: " + type);
        switch (type) {
            case GEMINI:
                GeminiLLMClient tempClient = new GeminiLLMClient(context);
                List<String> models = tempClient.getAvailableModels();
                // Filter and sort models
                models.removeIf(model -> model.contains("vision"));
                models.sort((a, b) -> {
                    if (a.contains("pro") && !b.contains("pro"))
                        return -1;
                    if (!a.contains("pro") && b.contains("pro"))
                        return 1;
                    return a.compareTo(b);
                });
                return models;
            case OPENAI:
                return Arrays.asList("gpt-4", "gpt-3.5-turbo");
            case STUB:
            default:
                return Arrays.asList("stub-model-1", "stub-model-2");
        }
    }
}
