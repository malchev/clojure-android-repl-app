package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

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
    protected CancellableCompletableFuture<AssistantResponse> sendMessages(ChatSession session, MessageFilter messageFilter) {
        Log.d(TAG, "Sending " + session.getMessages().size() + " messages in stub session: " + session.getSessionId());

        // Filter messages if filter is provided
        final List<Message> messagesToSend = filterMessages(session, messageFilter);

        CancellableCompletableFuture<AssistantResponse> future = new CancellableCompletableFuture<>();

        CompletableFuture.runAsync(() -> {
            try {
                // Check if cancelled before starting
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled before starting");
                    return;
                }

                // Find the latest user message
                String userMessage = "";
                for (int i = messagesToSend.size() - 1; i >= 0; i--) {
                    if (MessageRole.USER.equals(messagesToSend.get(i).role)) {
                        userMessage = messagesToSend.get(i).content;
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

                // Create AssistantResponse with model information
                AssistantResponse assistantResponse = new AssistantResponse(response, getType(), getModel());

                future.complete(assistantResponse);
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
