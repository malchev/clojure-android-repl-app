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
        public CompletableFuture<String> sendMessage(String message) {
            // Add user message to history
            messages.add(new Message("user", message));

            // If this is the first message, add system prompt
            if (messages.size() == 1) {
                messages.add(0, new Message("system", getSystemPrompt()));
            }

            // Generate a stub response
            return CompletableFuture.supplyAsync(() -> {
                // Simple stub response
                String response = generateStubResponse(message);

                // Add assistant message to history
                messages.add(new Message("assistant", response));

                return response;
            });
        }

        @Override
        public void reset() {
            messages.clear();
            Log.d(TAG, "Reset stub chat session: " + sessionId);
        }

        @Override
        public List<Message> getMessageHistory() {
            return messages;
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
        Log.d(TAG, "Generating initial code for description: " + description);
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
                "    \"Button created\"))", description.substring(0, Math.min(10, description.length())));
        Log.d(TAG, "StubLLM generated code: " + testProgram);
        return CompletableFuture.completedFuture(testProgram);
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        Log.d(TAG, "Generating next iteration with feedback: " + feedback);
        Log.d(TAG, "Screenshot present: " + (screenshot != null));
        Log.d(TAG, "Current code length: " + currentCode.length());
        // Just return the current code with a comment about the feedback
        String nextIteration = "; Feedback received: " + feedback + "\n" + currentCode;
        Log.d(TAG, "StubLLM generated next iteration: " + nextIteration);
        return CompletableFuture.completedFuture(nextIteration);
    }

    private String generateStubResponse(String message) {
        Log.d(TAG, "Generating stub response for: " + message);

        // Simple response with a clojure code block
        return "```clojure\n(ns example.core\n  (:gen-class))\n\n(defn -main [& args]\n  (println \"Hello, world!\"))\n```";
    }
}
