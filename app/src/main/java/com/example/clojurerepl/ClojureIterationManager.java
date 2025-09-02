package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.UUID;

import com.example.clojurerepl.session.DesignSession;

public class ClojureIterationManager {
    private static final String TAG = "ClojureIterationManager";

    /**
     * Callback interface for handling code extraction errors
     */
    public interface ExtractionErrorCallback {
        void onExtractionError(String errorMessage);
    }

    private final Context context;
    private final LLMClient llmClient;
    private final UUID sessionId;
    private ExtractionErrorCallback extractionErrorCallback;

    private ExecutorService executor;
    private LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> generationFuture;

    public ClojureIterationManager(Context context, DesignSession session) {
        this.context = context.getApplicationContext();
        this.llmClient = LLMClientFactory.createClient(context, session.getLlmType(), session.getLlmModel(),
                session.getChatSession());
        this.sessionId = session.getId();

        // Initialize executor for background tasks
        this.executor = Executors.newCachedThreadPool();
    }

    /**
     * Sets the callback for handling code extraction errors
     * 
     * @param callback The callback to handle extraction errors
     */
    public void setExtractionErrorCallback(ExtractionErrorCallback callback) {
        this.extractionErrorCallback = callback;
    }

    /**
     * Cancels the current code generation request if one is in progress
     * 
     * @return true if a request was cancelled, false if no request was in progress
     */
    public boolean cancelCurrentGeneration() {
        Log.d(TAG, "Attempting to cancel current generation");

        boolean cancelled = false;

        // Cancel the generation future if it exists and is not done
        if (generationFuture != null && !generationFuture.isDone()) {
            Log.d(TAG, "Cancelling generation future");
            cancelled = generationFuture.cancel(true);
        }

        // Cancel the underlying LLM client request
        if (llmClient != null) {
            Log.d(TAG, "Cancelling LLM client request");
            cancelled = llmClient.cancelCurrentRequest() || cancelled;
        }

        Log.d(TAG, "Cancellation result: " + cancelled);
        return cancelled;
    }

    /**
     * Generates initial Clojure code based on a description and optional initial
     * code.
     * This allows providing existing code as a starting point for the generation.
     * 
     * @param description The app description to generate code for
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return A CancellableCompletableFuture that will be completed with the
     *         AssistantMessage
     */
    public LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> generateInitialCode(String description,
            String initialCode) {
        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        Log.d(TAG, "Starting initial code generation with description: " + description +
                ", initial code provided: " + (initialCode != null && !initialCode.isEmpty()));

        // Create wrapper future that maintains CancellableCompletableFuture type
        LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> wrapperFuture = new LLMClient.CancellableCompletableFuture<>();
        generationFuture = wrapperFuture;

        // Call the LLM client which returns a CancellableCompletableFuture
        LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> llmFuture;
        if (initialCode != null && !initialCode.isEmpty()) {
            llmFuture = llmClient.generateInitialCode(sessionId, description, initialCode);
        } else {
            llmFuture = llmClient.generateInitialCode(sessionId, description);
        }

        // Handle the LLM response
        llmFuture.thenAccept(assistantMessage -> {
            Log.d(TAG, "Received initial response from LLM, length: " +
                    (assistantMessage.content != null ? assistantMessage.content.length() : "null"));

            // Check if code extraction succeeded (now handled by AssistantMessage)
            if (assistantMessage.getExtractedCode() == null) {
                // Use callback if available, otherwise fall back to exception
                if (extractionErrorCallback != null) {
                    extractionErrorCallback.onExtractionError("No code found in LLM response");
                    wrapperFuture.completeExceptionally(new IllegalArgumentException("No code found in LLM response"));
                } else {
                    wrapperFuture.completeExceptionally(new IllegalArgumentException("No code found in LLM response"));
                }
                return;
            }

            Log.d(TAG, "Extracted clean initial code. Length: " +
                    (assistantMessage.getExtractedCode() != null ? assistantMessage.getExtractedCode().length()
                            : "null"));

            wrapperFuture.complete(assistantMessage);
        }).exceptionally(ex -> {
            Log.e(TAG, "Error generating initial code", ex);
            wrapperFuture.completeExceptionally(ex);
            return null;
        });

        return wrapperFuture;
    }

    public LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> generateNextIteration(String description,
            String feedback,
            String code,
            String logcat,
            List<File> images) {
        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        Log.d(TAG, "Starting new code generation with description: " + description + ", feedback: " + feedback);

        // Create wrapper future that maintains CancellableCompletableFuture type
        LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> wrapperFuture = new LLMClient.CancellableCompletableFuture<>();
        generationFuture = wrapperFuture;

        // Call the LLM client which returns a CancellableCompletableFuture
        LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> llmFuture = llmClient
                .generateNextIteration(
                        sessionId,
                        description, // Pass the original description now
                        code,
                        logcat,
                        feedback,
                        images); // Pass the images list

        // Handle the LLM response
        llmFuture.thenAccept(assistantMessage -> {
            Log.d(TAG, "Received response from LLM, length: " +
                    (assistantMessage.content != null ? assistantMessage.content.length() : "null"));
            Log.d(TAG, "LLM response first 100 chars: " +
                    (assistantMessage.content != null && assistantMessage.content.length() > 100
                            ? assistantMessage.content.substring(0, 100)
                            : assistantMessage.content));

            // Check if code extraction succeeded (now handled by AssistantMessage)
            if (assistantMessage.getExtractedCode() == null) {
                // Use callback if available, otherwise fall back to exception
                if (extractionErrorCallback != null) {
                    extractionErrorCallback.onExtractionError("No code found in LLM response");
                    wrapperFuture.completeExceptionally(new IllegalArgumentException("No code found in LLM response"));
                } else {
                    wrapperFuture.completeExceptionally(new IllegalArgumentException("No code found in LLM response"));
                }
                return;
            }

            Log.d(TAG, "Extracted clean code. Length: " +
                    (assistantMessage.getExtractedCode() != null ? assistantMessage.getExtractedCode().length()
                            : "null"));

            wrapperFuture.complete(assistantMessage);
        }).exceptionally(ex -> {
            Log.e(TAG, "Error generating next iteration", ex);
            wrapperFuture.completeExceptionally(ex);
            return null;
        });

        return wrapperFuture;
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
