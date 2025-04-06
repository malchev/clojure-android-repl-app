package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.CompletableFuture;
import java.util.List;

public abstract class LLMClient {
    private static final String TAG = "LLMClient";
    private static final String PROMPT_TEMPLATE_PATH = "prompt.txt";

    protected final Context context;
    private String promptTemplate;

    public LLMClient(Context context) {
        Log.d(TAG, "Creating new LLMClient");
        this.context = context.getApplicationContext();
        loadPromptTemplate();
    }

    private void loadPromptTemplate() {
        Log.d(TAG, "Loading prompt template");
        try {
            // Try to load from assets
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(context.getAssets().open(PROMPT_TEMPLATE_PATH)))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append("\n");
                }
                promptTemplate = sb.toString();
                Log.d(TAG, "Loaded prompt template from assets");
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not load prompt template from assets: " + e.getMessage());
            // Provide a default prompt if loading fails
            promptTemplate = "Create a Clojure app for Android. Use the following Android APIs as needed: " +
                    "android.widget, android.view, android.graphics, com.google.android.material.";
        }
    }

    protected String formatInitialPrompt(String description) {
        Log.d(TAG, "Formatting initial prompt with description: " + description);
        return promptTemplate + "\n\nDesired app description:\n" + description;
    }

    protected String formatIterationPrompt(String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        Log.d(TAG, "Formatting iteration prompt with description: " + description +
                ", screenshot: " + (screenshot != null ? screenshot.getPath() : "null") +
                ", feedback: " + feedback);
        return String.format(
                "%s\n\nCurrent implementation:\n```clojure\n%s\n```\n\n" +
                        "Logcat output:\n```\n%s\n```\n\n" +
                        "User feedback:\n%s\n\n" +
                        "Please provide an improved version addressing the feedback.",
                promptTemplate, currentCode, logcat, feedback);
    }

    public abstract CompletableFuture<String> generateInitialCode(String description);

    public abstract CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback);

    // Message class for chat history
    public static class Message {
        public final String role;
        public final String content;

        public Message(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    // Chat session interface
    public interface ChatSession {
        void reset();

        CompletableFuture<String> sendMessage(String message);

        List<Message> getMessageHistory();
    }

    public abstract ChatSession getOrCreateSession(String description);
}
