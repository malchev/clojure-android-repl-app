package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import com.example.clojurerepl.session.DesignSession;

public class ClojureIterationManager {
    private static final String TAG = "ClojureIterationManager";

    private final Context context;
    private final LLMClient llmClient;
    private final UUID sessionId;

    private ExecutorService executor;
    private LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> currentFuture;

    public ClojureIterationManager(Context context, DesignSession session) {
        this.context = context.getApplicationContext();
        this.llmClient = LLMClientFactory.createClient(context, session.getLlmType(), session.getLlmModel(),
                session.getChatSession());
        this.sessionId = session.getId();

        // Initialize executor for background tasks
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * Sends messages using the LLM client and returns a cancellable future
     * 
     * @param chatSession The chat session containing messages to send
     * @return A CancellableCompletableFuture that will be completed with the
     *         AssistantMessage
     */
    public LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> sendMessages(
            LLMClient.ChatSession chatSession) {
        // Cancel any previous request that might be running
        if (currentFuture != null && !currentFuture.isDone()) {
            currentFuture.cancel(true);
        }

        Log.d(TAG, "Sending messages to LLM client");

        // Call sendMessages directly and store the future
        currentFuture = llmClient.sendMessages(chatSession);

        return currentFuture;
    }

    /**
     * Cancels the current request if one is in progress
     * 
     * @return true if a request was cancelled, false if no request was in progress
     */
    public boolean cancelCurrentRequest() {
        Log.d(TAG, "Attempting to cancel current request");

        boolean cancelled = false;

        // Cancel the current future if it exists and is not done
        if (currentFuture != null && !currentFuture.isDone()) {
            Log.d(TAG, "Cancelling current future");
            cancelled = currentFuture.cancel(true);
        }

        // Cancel the underlying LLM client request
        if (llmClient != null) {
            Log.d(TAG, "Cancelling LLM client request");
            cancelled = llmClient.cancelCurrentRequest() || cancelled;
        }

        Log.d(TAG, "Cancellation result: " + cancelled);
        return cancelled;
    }

    public void shutdown() {
        // Shutdown executor
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    public LLMClient getLLMClient() {
        return llmClient;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    /**
     * Get model properties for the current LLM client's model
     *
     * @return ModelProperties for the current model, or null if not found
     */
    public LLMClient.ModelProperties getModelProperties() {
        if (llmClient == null) {
            return null;
        }

        String modelName = llmClient.getModel();
        if (modelName == null) {
            return null;
        }

        // Delegate to the specific LLM client implementation
        switch (llmClient.getType()) {
            case GEMINI:
                return com.example.clojurerepl.GeminiLLMClient.getModelProperties(modelName);
            case OPENAI:
                return com.example.clojurerepl.OpenAIChatClient.getModelProperties(modelName);
            case CLAUDE:
                return com.example.clojurerepl.ClaudeLLMClient.getModelProperties(modelName);
            case STUB:
                return com.example.clojurerepl.StubLLMClient.getModelProperties(modelName);
            default:
                return null;
        }
    }
}
