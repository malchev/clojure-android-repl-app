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

    private CompletableFuture<IterationResult> currentIterationFuture;
    private ExecutorService executor;
    private CompletableFuture<IterationResult> renderFuture;
    private CompletableFuture<String> generationFuture;
    private IterationResult lastResult;

    public static class IterationResult {
        public final String code;
        public final String logcat;
        public final File screenshot;
        public final boolean success;
        public final String feedback;

        public IterationResult(String code, String logcat, File screenshot,
                boolean success, String feedback) {
            this.code = code;
            this.logcat = logcat;
            this.screenshot = screenshot;
            this.success = success;
            this.feedback = feedback;
        }
    }

    private final List<IterationResult> iterationHistory = new ArrayList<>();

    /**
     * Result class for code extraction operations
     */
    public static class CodeExtractionResult {
        public final String code;
        public final String textBeforeCode;
        public final String textAfterCode;
        public final boolean success;
        public final String errorMessage;

        private CodeExtractionResult(String code, String textBeforeCode, String textAfterCode, boolean success,
                String errorMessage) {
            this.code = code;
            this.textBeforeCode = textBeforeCode;
            this.textAfterCode = textAfterCode;
            this.success = success;
            this.errorMessage = errorMessage;
        }

        public static CodeExtractionResult success(String code, String textBeforeCode, String textAfterCode) {
            return new CodeExtractionResult(code, textBeforeCode, textAfterCode, true, null);
        }

        public static CodeExtractionResult failure(String errorMessage) {
            return new CodeExtractionResult(null, null, null, false, errorMessage);
        }
    }

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

        // Cancel the current iteration future if it exists and is not done
        if (currentIterationFuture != null && !currentIterationFuture.isDone()) {
            Log.d(TAG, "Cancelling current iteration future");
            cancelled = currentIterationFuture.cancel(true) || cancelled;
        }

        // Cancel the render future if it exists and is not done
        if (renderFuture != null && !renderFuture.isDone()) {
            Log.d(TAG, "Cancelling render future");
            cancelled = renderFuture.cancel(true) || cancelled;
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
     * Generates initial Clojure code based on a description.
     * This is the entry point for starting a new design.
     * 
     * @param description The app description to generate code for
     * @return A CompletableFuture that will be completed with the generated code
     */
    public CompletableFuture<String> generateInitialCode(String description) {
        return generateInitialCode(description, null);
    }

    /**
     * Generates initial Clojure code based on a description and optional initial
     * code.
     * This allows providing existing code as a starting point for the generation.
     * 
     * @param description The app description to generate code for
     * @param initialCode Optional initial code to use as a starting point (may be
     *                    null)
     * @return A CompletableFuture that will be completed with the generated code
     */
    public CompletableFuture<String> generateInitialCode(String description, String initialCode) {
        // Use sessionId directly as it's already a UUID
        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        Log.d(TAG, "Starting initial code generation with description: " + description +
                ", initial code provided: " + (initialCode != null && !initialCode.isEmpty()));

        // Create a new future for this generation
        generationFuture = new CompletableFuture<>();

        // Run in background thread
        executor.execute(() -> {
            try {
                // Call the LLM client which returns a CancellableCompletableFuture
                LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> llmFuture;
                if (initialCode != null && !initialCode.isEmpty()) {
                    llmFuture = llmClient.generateInitialCode(sessionId, description, initialCode);
                } else {
                    llmFuture = llmClient.generateInitialCode(sessionId, description);
                }

                // When the LLM response is ready, complete our future
                llmFuture.thenAccept(assistantMessage -> {
                    Log.d(TAG, "Received initial response from LLM, length: " +
                            (assistantMessage.content != null ? assistantMessage.content.length() : "null"));

                    // Extract clean code from markdown blocks if present
                    CodeExtractionResult extractionResult = extractClojureCode(assistantMessage.content);
                    if (!extractionResult.success) {
                        // Use callback if available, otherwise fall back to exception
                        if (extractionErrorCallback != null) {
                            extractionErrorCallback.onExtractionError(extractionResult.errorMessage);
                            generationFuture.cancel(true);
                        } else {
                            generationFuture
                                    .completeExceptionally(new IllegalArgumentException(extractionResult.errorMessage));
                        }
                        return;
                    }
                    String cleanCode = extractionResult.code;
                    Log.d(TAG, "Extracted clean initial code. Length: " +
                            (cleanCode != null ? cleanCode.length() : "null"));

                    generationFuture.complete(cleanCode);
                }).exceptionally(ex -> {
                    generationFuture.completeExceptionally(ex);
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Error generating initial code", e);
                generationFuture.completeExceptionally(e);
            }
        });

        return generationFuture;
    }

    /**
     * Extracts clean Clojure code from text that may contain markdown code block
     * markers
     * Helper method to ensure consistent code extraction across the app
     * 
     * @param input The input text that may contain markdown code blocks
     * @return A CodeExtractionResult containing the extracted code or error
     *         information
     */
    public static CodeExtractionResult extractClojureCode(String input) {
        if (input == null || input.isEmpty()) {
            Log.d(TAG, "Input is null or empty, returning empty string");
            return CodeExtractionResult.success("", "", "");
        }

        // Check if the input contains markdown code block markers
        // Find the LAST occurrence of ```clojure to ensure we get the final code block
        // even if the LLM includes multiple code blocks in violation of the system
        // prompt
        int startMarkerPos = input.lastIndexOf("```clojure");
        if (startMarkerPos != -1) {
            Log.d(TAG, "Found starting marker at position: " + startMarkerPos);
            try {
                // Look for a code block
                // Find the end of the start marker line
                int lineEnd = input.indexOf('\n', startMarkerPos);
                if (lineEnd != -1) {
                    // Skip past the entire marker line
                    int codeStart = lineEnd + 1;

                    // Find the closing code block marker
                    int endMarkerPos = input.indexOf("```", codeStart);
                    if (endMarkerPos != -1) {
                        // Extract just the code between the markers
                        String extractedCode = input.substring(codeStart, endMarkerPos).trim();

                        // Extract text before and after the code block
                        String textBeforeCode = input.substring(0, startMarkerPos).trim();
                        String textAfterCode = input.substring(endMarkerPos + 3).trim(); // +3 for "```"

                        Log.d(TAG, "Successfully extracted code. Length: " + extractedCode.length());
                        return CodeExtractionResult.success(extractedCode, textBeforeCode, textAfterCode);
                    }
                    Log.d(TAG, "Could not find closing marker.");
                    return CodeExtractionResult.failure("No closing code block marker found in response");
                } else {
                    Log.e(TAG, "Starting marker line does not end with newline.");
                    return CodeExtractionResult.failure("Starting marker line does not end with newline");
                }
            } catch (Exception e) {
                Log.e(TAG, "Error extracting code", e);
                return CodeExtractionResult.failure("Failed to extract code from response: " + e.getMessage());
            }
        }

        // If we couldn't extract a code block or there are no markers, return the
        // original text as textBeforeCode
        Log.d(TAG, "No code block markers found in response");
        return CodeExtractionResult.success("", input.trim(), "");
    }

    public CompletableFuture<String> generateNextIteration(String description, String feedback,
            IterationResult lastResult, List<File> images) {
        // Cancel any previous generation task that might be running
        if (generationFuture != null && !generationFuture.isDone()) {
            generationFuture.cancel(true);
        }

        Log.d(TAG, "Starting new code generation with description: " + description + ", feedback: " + feedback);

        // Create a new future for this generation
        generationFuture = new CompletableFuture<>();

        // Store the result for future reference
        this.lastResult = lastResult;

        // Use sessionId directly as it's already a UUID

        // Run in background thread
        executor.execute(() -> {
            try {
                // Call the LLM client which returns a CancellableCompletableFuture - PASS
                // DESCRIPTION HERE
                LLMClient.CancellableCompletableFuture<LLMClient.AssistantMessage> llmFuture = llmClient
                        .generateNextIteration(
                                sessionId,
                                description, // Pass the original description now
                                lastResult.code,
                                lastResult.logcat,
                                feedback,
                                images); // Pass the images list

                // When the LLM response is ready, complete our future
                llmFuture.thenAccept(assistantMessage -> {
                    Log.d(TAG, "Received response from LLM, length: " +
                            (assistantMessage.content != null ? assistantMessage.content.length() : "null"));
                    Log.d(TAG, "LLM response first 100 chars: " +
                            (assistantMessage.content != null && assistantMessage.content.length() > 100
                                    ? assistantMessage.content.substring(0, 100)
                                    : assistantMessage.content));

                    // Extract clean code from markdown blocks if present
                    CodeExtractionResult extractionResult = extractClojureCode(assistantMessage.content);
                    if (!extractionResult.success) {
                        // Use callback if available, otherwise fall back to exception
                        if (extractionErrorCallback != null) {
                            extractionErrorCallback.onExtractionError(extractionResult.errorMessage);
                            generationFuture.cancel(true);
                        } else {
                            generationFuture
                                    .completeExceptionally(new IllegalArgumentException(extractionResult.errorMessage));
                        }
                        return;
                    }
                    String cleanCode = extractionResult.code;
                    Log.d(TAG, "Extracted clean code. Length: " +
                            (cleanCode != null ? cleanCode.length() : "null"));

                    generationFuture.complete(cleanCode);
                }).exceptionally(ex -> {
                    generationFuture.completeExceptionally(ex);
                    return null;
                });
            } catch (Exception e) {
                Log.e(TAG, "Error generating next iteration", e);
                generationFuture.completeExceptionally(e);
            }
        });

        return generationFuture;
    }

    public IterationResult getLastResult() {
        if (iterationHistory.isEmpty()) {
            return null;
        }
        return iterationHistory.get(iterationHistory.size() - 1);
    }

    public void addIterationResult(IterationResult result) {
        iterationHistory.add(result);
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
