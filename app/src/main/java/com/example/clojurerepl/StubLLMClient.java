package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class StubLLMClient extends LLMClient {
    private static final String TAG = "StubLLMClient";
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    public StubLLMClient(Context context) {
        super(context);
        Log.d(TAG, "Created new StubLLMClient");
    }

    private class StubChatSession implements ChatSession {
        private String sessionId;
        private List<Message> messages = new ArrayList<>();

        public StubChatSession(String sessionId) {
            this.sessionId = sessionId;
            Log.d(TAG, "Created new stub chat session: " + sessionId);
        }

        @Override
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Queuing system prompt in stub session: " + sessionId);
            messages.add(new Message("system", content));
        }

        @Override
        public void queueUserMessage(String content) {
            Log.d(TAG, "Queuing user message in stub session: " + sessionId);
            messages.add(new Message("user", content));
        }

        @Override
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in stub session: " + sessionId);
            messages.add(new Message("assistant", content));
        }

        @Override
        public CompletableFuture<String> sendMessages() {
            Log.d(TAG, "Sending " + messages.size() + " messages in stub session: " + sessionId);

            return CompletableFuture.supplyAsync(() -> {
                // Find the latest user message
                String userMessage = "";
                for (int i = messages.size() - 1; i >= 0; i--) {
                    if ("user".equals(messages.get(i).role)) {
                        userMessage = messages.get(i).content;
                        break;
                    }
                }

                // Generate stub response
                String response = generateStubResponse(userMessage);

                // Add assistant message to history
                queueAssistantResponse(response);

                return response;
            });
        }

        @Override
        public void reset() {
            messages.clear();
            Log.d(TAG, "Reset stub chat session: " + sessionId);
        }
    }

    @Override
    public ChatSession getOrCreateSession(String description) {
        String sessionId = "stub-" + Math.abs(description.hashCode());
        if (!chatSessions.containsKey(sessionId)) {
            chatSessions.put(sessionId, new StubChatSession(sessionId));
        }
        return chatSessions.get(sessionId);
    }

    @Override
    public boolean clearApiKey() {
        // No-op for stub client
        return true;
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description) {
        Log.d(TAG, "Generating initial code for description: " + description + " using stub client");

        // Get or create a session for this app description
        ChatSession session = preparePromptForInitialCode(description);

        // Send all messages and get the response
        return session.sendMessages();
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        Log.d(TAG, "Generating next iteration for description: " + description + " using stub client");

        // Get existing session for this app description
        ChatSession session = getOrCreateSession(description);

        // Format the iteration prompt
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback);

        // Queue the user message
        session.queueUserMessage(prompt);

        // Send all messages and get the response
        return session.sendMessages();
    }

    private String generateStubResponse(String message) {
        Log.d(TAG, "Generating stub response for: " + message);
        // Return a simple test program for now
        String testProgram = String.format(";; Button test\n" +
                "(defn -main []\n" +
                "  (let [context *context*\n" +
                "        button (doto (new android.widget.Button context)\n" +
                "                (.setText \"%s\")\n" +
                "                (.setTextSize 24.0)\n" +
                "                (.setPadding 20 20 20 20)\n" +
                "                (.setBackgroundColor (unchecked-int 0xFF4CAF50))\n" +
                "                (.setTextColor (unchecked-int 0xFFFFFFFF)))\n" +
                "        params (doto (new android.widget.LinearLayout$LayoutParams\n" +
                "                        android.widget.LinearLayout$LayoutParams/MATCH_PARENT\n" +
                "                        android.widget.LinearLayout$LayoutParams/WRAP_CONTENT)\n" +
                "                  (.setMargins 20 20 20 20))]\n" +
                "    (.setOnClickListener button\n" +
                "      (reify android.view.View$OnClickListener\n" +
                "        (onClick [this view]\n" +
                "          (.setText button \"Clicked!\"))))\n" +
                "    (.addView *content-layout* button params)\n" +
                "    \"Button created\"))", message.substring(0, Math.min(10, message.length())));
        Log.d(TAG, "StubLLM generated code: " + testProgram);
        return testProgram;
    }
}
