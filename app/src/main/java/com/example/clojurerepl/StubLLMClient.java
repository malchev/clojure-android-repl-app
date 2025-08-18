package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CompletableFuture;

import java.util.List;
import java.util.ArrayList;
import java.util.UUID;

public class StubLLMClient extends LLMClient {
    private static final String TAG = "StubLLMClient";

    public StubLLMClient(Context context, ChatSession chatSession) {
        super(context, chatSession);
        Log.d(TAG, "Created new StubLLMClient");
    }

    @Override
    protected CancellableCompletableFuture<String> sendMessages(ChatSession session) {
        Log.d(TAG, "Sending " + session.getMessages().size() + " messages in stub session: " + session.getSessionId());

        CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                // Check if cancelled before starting
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled before starting");
                    return;
                }

                // Find the latest user message
                String userMessage = "";
                for (int i = session.getMessages().size() - 1; i >= 0; i--) {
                    if (MessageRole.USER.equals(session.getMessages().get(i).role)) {
                        userMessage = session.getMessages().get(i).content;
                        break;
                    }
                }

                // Generate stub response
                String response = generateStubResponse(userMessage);

                // Check if cancelled after generating response
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled after generating response");
                    return;
                }

                // Add assistant message to history
                session.queueAssistantResponse(response, getType(), getModel());

                future.complete(response);
            } catch (Exception e) {
                Log.e(TAG, "Error in stub chat session", e);
                if (!future.isCancelled()) {
                    future.completeExceptionally(new RuntimeException("Failed to get response from stub", e));
                }
            }
        });

        return future;
    }

    @Override
    public boolean clearApiKey() {
        // No-op for stub client
        return true;
    }

    @Override
    public boolean cancelCurrentRequest() {
        // No-op for stub client - no actual requests to cancel
        return false;
    }

    @Override
    public LLMClientFactory.LLMType getType() {
        return LLMClientFactory.LLMType.STUB;
    }

    @Override
    public String getModel() {
        return "stub-model";
    }

    /**
     * Get model properties for a specific model
     * 
     * @param modelName The name of the model
     * @return ModelProperties for the model, or null if not found
     */
    public static ModelProperties getModelProperties(String modelName) {
        // TODO: Implement Stub model properties lookup table
        return null;
    }

    @Override
    public CancellableCompletableFuture<String> generateInitialCode(UUID sessionId, String description) {
        Log.d(TAG, "Generating initial code for description: " + description + " using stub client");

        // Queue system prompt and format initial prompt
        chatSession.queueSystemPrompt(getSystemPrompt());
        String prompt = formatInitialPrompt(description, null);
        chatSession.queueUserMessage(prompt, null, null, null);

        // Send all messages and get the response
        CancellableCompletableFuture<String> future = sendMessages(chatSession);
        CancellableCompletableFuture<String> wrappedFuture = new CancellableCompletableFuture<>();

        future.handle((response, ex) -> {
            if (ex != null) {
                // Remove the messages we added before sendMessages (system prompt + user
                // message = 2 messages)
                chatSession.removeLastMessages(2);
                Log.d(TAG, "Removed 2 messages (system prompt + user message) due to failure");
                wrappedFuture.completeExceptionally(ex);
            } else {
                wrappedFuture.complete(response);
            }
            return null;
        });

        return wrappedFuture;
    }

    @Override
    public CancellableCompletableFuture<String> generateInitialCode(UUID sessionId, String description,
            String initialCode) {
        Log.d(TAG, "Generating initial code for description: " + description +
                " using stub client, with initial code: " + (initialCode != null ? "yes" : "no"));

        // Queue system prompt and format initial prompt with existing code as base
        chatSession.queueSystemPrompt(getSystemPrompt());
        String prompt = formatInitialPrompt(description, initialCode);
        chatSession.queueUserMessage(prompt, null, null, initialCode);

        // Send all messages and get the response
        CancellableCompletableFuture<String> future = sendMessages(chatSession);
        CancellableCompletableFuture<String> wrappedFuture = new CancellableCompletableFuture<>();

        future.handle((response, ex) -> {
            if (ex != null) {
                // Remove the messages we added before sendMessages (system prompt + user
                // message = 2 messages)
                chatSession.removeLastMessages(2);
                Log.d(TAG, "Removed 2 messages (system prompt + user message) due to failure");
                wrappedFuture.completeExceptionally(ex);
            } else {
                wrappedFuture.complete(response);
            }
            return null;
        });

        return wrappedFuture;
    }

    @Override
    public CancellableCompletableFuture<String> generateNextIteration(
            UUID sessionId,
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback,
            File image) {
        Log.d(TAG, "Generating next iteration for description: " + description + " using stub client");

        // Check if image is provided and model is multimodal
        if (image != null) {
            ModelProperties props = getModelProperties(getModel());
            if (props == null || !props.isMultimodal) {
                CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
                future.completeExceptionally(
                        new UnsupportedOperationException("Image parameter provided but model is not multimodal"));
                return future;
            }
        }

        // Format the iteration prompt
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback, image != null);

        // Queue the user message (with image attachment if provided)
        chatSession.queueUserMessageWithImage(prompt, image, logcat, feedback, null);

        // Send all messages and get the response
        CancellableCompletableFuture<String> future = sendMessages(chatSession);
        CancellableCompletableFuture<String> wrappedFuture = new CancellableCompletableFuture<>();

        future.handle((response, ex) -> {
            if (ex != null) {
                // Remove the user message we added before sendMessages (1 message)
                chatSession.removeLastMessages(1);
                Log.d(TAG, "Removed 1 message (user message) due to failure in generateNextIteration");
                wrappedFuture.completeExceptionally(ex);
            } else {
                wrappedFuture.complete(response);
            }
            return null;
        });

        return wrappedFuture;
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
