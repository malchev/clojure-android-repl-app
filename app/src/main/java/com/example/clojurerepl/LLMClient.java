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

    /**
     * Gets the LLM type for this client
     * 
     * @return The LLMType enum value
     */
    public abstract LLMClientFactory.LLMType getType();

    /**
     * Gets the current model name for this client
     * 
     * @return The model name as a string
     */
    public abstract String getModel();

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
        return "Implement the following app:\n" + description;
    }

    /**
     * Format initial prompt with option to provide starting code
     * 
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return Formatted prompt string
     */
    protected String formatInitialPrompt(String description, String initialCode) {
        Log.d(TAG, "Formatting initial prompt with description: " + description +
                ", initial code provided: " + (initialCode != null && !initialCode.isEmpty()));

        if (initialCode != null && !initialCode.isEmpty()) {
            return "Implement the following app:\n" + description +
                    "\n\nUse the following code as a starting point. Improve and expand upon it to meet all requirements:\n```clojure\n"
                    +
                    initialCode + "\n```";
        } else {
            return formatInitialPrompt(description);
        }
    }

    /**
     * Returns the system prompt to be used when initializing a conversation.
     * This prompt provides instruction on how to generate Clojure code.
     */
    protected String getSystemPrompt() {
        return promptTemplate + "\n\nAlways respond with Clojure code in a markdown code block.";
    }

    public ChatSession preparePromptForInitialCode(String description) {
        // Get or create a session for this app description
        ChatSession session = getOrCreateSession(description);

        // Reset session to start fresh
        session.reset();

        // Make sure we have a system message at the beginning
        session.queueSystemPrompt(getSystemPrompt());

        // Format the prompt using the helper from LLMClient
        String prompt = formatInitialPrompt(description);

        // Queue the user message and send all messages
        session.queueUserMessage(prompt);
        return session;
    }

    /**
     * Prepares a prompt for initial code generation with optional starting code
     * 
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return The prepared ChatSession
     */
    public ChatSession preparePromptForInitialCode(String description, String initialCode) {
        // Get or create a session for this app description
        ChatSession session = getOrCreateSession(description);

        // Reset session to start fresh
        session.reset();

        // Make sure we have a system message at the beginning
        session.queueSystemPrompt(getSystemPrompt());

        // Format the prompt using the helper from LLMClient with initial code
        String prompt = formatInitialPrompt(description, initialCode);

        // Queue the user message and send all messages
        session.queueUserMessage(prompt);
        return session;
    }

    public abstract CompletableFuture<String> generateInitialCode(String description);

    /**
     * Generate initial code with optional starting code
     * 
     * @param description The app description
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return A CompletableFuture containing the generated code
     */
    public abstract CompletableFuture<String> generateInitialCode(String description, String initialCode);

    protected String formatIterationPrompt(String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        Log.d(TAG, "Formatting iteration prompt with description: " + description +
                ", screenshot: " + (screenshot != null ? screenshot.getPath() : "null") +
                ", feedback: " + feedback);
        return String.format(
                "The app does not work. Provide an improved version addressing the user feedback and logcat output.\n" +
                        "User feedback: %s\n" +
                        "Logcat output:\n```\n%s\n```\n\n",
                feedback,
                logcat);
    }

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
        /**
         * Resets the chat session by clearing all messages
         */
        void reset();

        /**
         * Queues a system prompt message to be sent in the next API call
         *
         * @param content The system prompt content
         */
        void queueSystemPrompt(String content);

        /**
         * Queues a user message to be sent in the next API call
         *
         * @param content The user message content
         */
        void queueUserMessage(String content);

        /**
         * Queues an assistant (model) response to be sent in the next API call
         * Typically used when loading previous conversations
         *
         * @param content The assistant response content
         */
        void queueAssistantResponse(String content);

        /**
         * Sends all queued messages to the API and returns the response
         *
         * @return A CompletableFuture containing the extracted code response
         */
        CompletableFuture<String> sendMessages();

        /**
         * Retrieves the current list of messages in the chat session
         *
         * @return A list of Message objects representing the chat history
         */
        List<Message> getMessages();
    }

    public abstract ChatSession getOrCreateSession(String description);

    /**
     * Clears the API key for this client type
     *
     * @return true if key was successfully cleared, false otherwise
     */
    public abstract boolean clearApiKey();

    /**
     * Extracts Clojure code from a response that may contain markdown formatting.
     * Looks for code blocks marked with ```clojure or ``` tags.
     * Returns the original response if no code blocks are found.
     */
    protected String extractClojureCode(String response) {
        Log.d(TAG, "=== Extracting Clojure code from response length: " + response.length() + " ===");

        if (response == null || response.isEmpty()) {
            Log.w(TAG, "Empty response received");
            return "";
        }

        // First try to find ```clojure
        String startTag = "```clojure";
        int startIndex = response.indexOf(startTag);

        // If not found, try just ```
        if (startIndex == -1) {
            startTag = "```";
            startIndex = response.indexOf(startTag);
        }

        if (startIndex != -1) {
            // Move past the start tag and any whitespace/newline
            int codeStart = startIndex + startTag.length();
            while (codeStart < response.length() &&
                    (response.charAt(codeStart) == ' ' ||
                            response.charAt(codeStart) == '\n' ||
                            response.charAt(codeStart) == '\r')) {
                codeStart++;
            }

            // Find the closing ```
            String endTag = "```";
            int endIndex = response.indexOf(endTag, codeStart);

            if (endIndex != -1) {
                String code = response.substring(codeStart, endIndex).trim();
                Log.d(TAG, "Found code block between markers. Length: " + code.length());
                return code;
            } else {
                Log.w(TAG, "Found start marker but no end marker");
            }
        } else {
            Log.w(TAG, "No code block markers found in response");
        }

        // If we couldn't extract code between markers, return original
        return response;
    }
}
