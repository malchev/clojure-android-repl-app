package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;

import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;
import com.example.clojurerepl.auth.ApiKeyManager;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;

/**
 * Implementation of LLMClient for Google's Gemini API.
 * Based on API documentation: https://ai.google.dev/api/gemini_api
 */
public class GeminiLLMClient extends LLMClient {
    private static final String TAG = "GeminiLLMClient";
    private static final String API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private String currentModel = null;
    private ApiKeyManager apiKeyManager;

    private static final int HTTP_TIMEOUT = 120000; // 120 seconds timeout (increased from 30)
    private static final int MAX_RETRIES = 1;
    private static final int RETRY_DELAY_MS = 2000; // 2 seconds between retries

    // Track the current request for cancellation
    private final AtomicReference<CancellableCompletableFuture<AssistantResponse>> currentRequest = new AtomicReference<>();

    // Static cache for available models
    private static List<String> cachedModels = null;

    /**
     * Enum representing the status of a Gemini API response
     */
    public enum ResponseStatus {
        SUCCESS("Success"),
        MAX_TOKENS(
                "Response was truncated due to token limit. Please try with a shorter prompt or use a model with higher token limits."),
        SAFETY_BLOCKED("Response was blocked due to safety filters. Please try rephrasing your request."),
        RECITATION_BLOCKED("Response was blocked due to recitation detection. Please try rephrasing your request."),
        NO_CANDIDATES("No candidates in response"),
        EMPTY_CANDIDATES("No candidates available"),
        NO_CONTENT("No content in candidate"),
        PARSE_ERROR("Could not extract text from response"),
        API_ERROR("API returned an error"),
        UNKNOWN_ERROR("Unknown error occurred");

        private final String message;

        ResponseStatus(String message) {
            this.message = message;
        }

        public String getMessage() {
            return message;
        }
    }

    /**
     * Class to hold the result of text extraction
     */
    public static class ExtractionResult {
        private final ResponseStatus status;
        private final String text;
        private final String rawResponse;

        public ExtractionResult(ResponseStatus status, String text, String rawResponse) {
            this.status = status;
            this.text = text;
            this.rawResponse = rawResponse;
        }

        public ResponseStatus getStatus() {
            return status;
        }

        public String getText() {
            return text;
        }

        public String getRawResponse() {
            return rawResponse;
        }

        public boolean isSuccess() {
            return status == ResponseStatus.SUCCESS;
        }

        public String getErrorMessage() {
            return status.getMessage();
        }

        @Override
        public String toString() {
            return "ExtractionResult{" +
                    "status=" + status +
                    ", text=" + (text != null ? text.substring(0, Math.min(100, text.length())) + "..." : "null") +
                    ", rawResponse="
                    + (rawResponse != null ? rawResponse.substring(0, Math.min(200, rawResponse.length())) + "..."
                            : "null")
                    +
                    '}';
        }
    }

    /**
     * Static lookup table for Gemini model properties
     * Based on Firebase AI Logic and Google Cloud Vertex AI documentation
     * https://firebase.google.com/docs/ai-logic/models
     * https://cloud.google.com/vertex-ai/generative-ai/docs/models
     */
    private static final Map<String, ModelProperties> MODEL_PROPERTIES = new HashMap<>();

    static {
        // Gemini 2.5 Pro models
        MODEL_PROPERTIES.put("gemini-2.5-pro", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Stable"));

        // Gemini 2.5 Flash models
        MODEL_PROPERTIES.put("gemini-2.5-flash", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Stable"));

        // Gemini 2.0 Pro models
        MODEL_PROPERTIES.put("gemini-2.0-pro", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-pro-exp", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Experimental"));

        // Gemini 2.0 Flash models
        MODEL_PROPERTIES.put("gemini-2.0-flash", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-001", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-exp", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Experimental"));

        // Gemini 2.0 Flash Lite models
        MODEL_PROPERTIES.put("gemini-2.0-flash-lite", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-lite-001", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        // Gemini 1.5 Pro models (upcoming retirement)
        MODEL_PROPERTIES.put("gemini-1.5-pro", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-pro-001", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-pro-002", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-pro-latest", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        // Gemini 1.5 Flash models (upcoming retirement)
        MODEL_PROPERTIES.put("gemini-1.5-flash", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-flash-001", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-flash-002", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-flash-latest", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        // Gemini 1.5 Flash 8B models (upcoming retirement)
        MODEL_PROPERTIES.put("gemini-1.5-flash-8b", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-flash-8b-001", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        MODEL_PROPERTIES.put("gemini-1.5-flash-8b-latest", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Upcoming Retirement"));

        // Gemini 1.0 Pro models (retired)
        MODEL_PROPERTIES.put("gemini-1.0-pro", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                false, // text-only
                "Retired"));

        MODEL_PROPERTIES.put("gemini-1.0-pro-001", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                false, // text-only
                "Retired"));

        MODEL_PROPERTIES.put("gemini-1.0-pro-002", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                false, // text-only
                "Retired"));

        // Gemini 1.0 Pro Vision models (retired)
        MODEL_PROPERTIES.put("gemini-1.0-pro-vision", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                true, // multimodal
                "Retired"));

        MODEL_PROPERTIES.put("gemini-1.0-pro-vision-001", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                true, // multimodal
                "Retired"));

        MODEL_PROPERTIES.put("gemini-1.0-pro-vision-latest", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                true, // multimodal
                "Retired"));

        // Legacy gemini-pro-vision (retired)
        MODEL_PROPERTIES.put("gemini-pro-vision", new ModelProperties(
                30_720, // 30,720 tokens input context
                2_048, // 2,048 tokens output
                true, // multimodal
                "Retired"));

        // Experimental and preview models
        MODEL_PROPERTIES.put("gemini-2.5-pro-exp-03-25", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Experimental"));

        MODEL_PROPERTIES.put("gemini-2.5-pro-preview-03-25", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-pro-preview-05-06", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-pro-preview-06-05", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-pro-preview-tts", new ModelProperties(
                2_000_000, // ~2M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with TTS
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-flash-lite-preview-06-17", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-flash-preview-04-17", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-flash-preview-04-17-thinking", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with thinking
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-flash-preview-05-20", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.5-flash-preview-tts", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with TTS
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-lite-preview", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-lite-preview-02-05", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Preview"));

        MODEL_PROPERTIES.put("gemini-2.0-pro-exp-02-05", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal
                "Experimental"));

        // Thinking models
        MODEL_PROPERTIES.put("gemini-2.0-flash-thinking-exp", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with thinking
                "Experimental"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-thinking-exp-01-21", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with thinking
                "Experimental"));

        MODEL_PROPERTIES.put("gemini-2.0-flash-thinking-exp-1219", new ModelProperties(
                1_000_000, // ~1M tokens input context
                8_192, // 8,192 tokens output
                true, // multimodal with thinking
                "Experimental"));

        // Embedding models
        MODEL_PROPERTIES.put("gemini-embedding-exp", new ModelProperties(
                2_048, // 2,048 tokens input context
                768, // 768-dimensional embeddings
                false, // embedding model
                "Experimental"));

        MODEL_PROPERTIES.put("gemini-embedding-exp-03-07", new ModelProperties(
                2_048, // 2,048 tokens input context
                768, // 768-dimensional embeddings
                false, // embedding model
                "Experimental"));

        // Other experimental models
        MODEL_PROPERTIES.put("gemini-exp-1206", new ModelProperties(
                1_000_000, // ~1M tokens input context (estimated)
                8_192, // 8,192 tokens output (estimated)
                true, // multimodal (estimated)
                "Experimental"));
    }

    /**
     * Get model properties for a specific model
     *
     * @param modelName The name of the model
     * @return ModelProperties for the model, or null if not found
     */
    public static ModelProperties getModelProperties(String modelName) {
        return MODEL_PROPERTIES.get(modelName);
    }

    /**
     * Get the maximum input tokens for a model
     *
     * @param modelName The name of the model
     * @return Maximum input tokens, or default value if model not found
     */
    public static int getMaxInputTokens(String modelName) {
        ModelProperties props = getModelProperties(modelName);
        return props != null ? props.maxInputTokens : 1_000_000; // Default to 1M tokens
    }

    /**
     * Get the maximum output tokens for a model
     *
     * @param modelName The name of the model
     * @return Maximum output tokens, or default value if model not found
     */
    public static int getMaxOutputTokens(String modelName) {
        ModelProperties props = getModelProperties(modelName);
        return props != null ? props.maxOutputTokens : 8_192; // Default to 8,192 tokens
    }

    /**
     * Check if a model is multimodal
     *
     * @param modelName The name of the model
     * @return true if multimodal, false otherwise
     */
    public static boolean isMultimodal(String modelName) {
        ModelProperties props = getModelProperties(modelName);
        return props != null ? props.isMultimodal : true; // Default to true for safety
    }

    /**
     * Get the status of a model
     *
     * @param modelName The name of the model
     * @return Status string, or "Unknown" if model not found
     */
    public static String getModelStatus(String modelName) {
        ModelProperties props = getModelProperties(modelName);
        return props != null ? props.status : "Unknown";
    }

    public GeminiLLMClient(Context context, ChatSession chatSession) {
        super(context, chatSession);
        Log.d(TAG, "Creating new GeminiLLMClient with " + chatSession.getMessages().size() + " messages.");
        apiKeyManager = ApiKeyManager.getInstance(context);
    }

    @Override
    protected CancellableCompletableFuture<AssistantResponse> sendMessages(ChatSession session, MessageFilter messageFilter) {
        Log.d(TAG, "Sending " + session.getMessages().size() + " messages in session: " + session.getSessionId());
        Log.d(TAG, "System prompt available: " + (session.hasSystemPrompt() ? "yes" : "no"));

        // Cancel any existing request
        cancelCurrentRequest();

        // Filter messages if filter is provided
        final List<Message> messagesToSend = filterMessages(session, messageFilter);

        // Create a new cancellable future
        CancellableCompletableFuture<AssistantResponse> future = new CancellableCompletableFuture<>();
        currentRequest.set(future);

        CompletableFuture.runAsync(() -> {
            try {
                // Check if cancelled before starting
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled before starting");
                    return;
                }

                // Call the API with the filtered messages and system prompt
                ExtractionResult extractionResult = callGeminiAPI(messagesToSend, session.getSystemPrompt(), future);

                // Check if cancelled after API call
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled after API call");
                    return;
                }

                // Create AssistantResponse with model information
                String responseText = extractionResult != null ? extractionResult.getText() : null;
                if (responseText == null && extractionResult != null) {
                    responseText = extractionResult.getErrorMessage();
                }
                if (responseText == null) {
                    responseText = "";
                }
                AssistantResponse.CompletionStatus completionStatus =
                        extractionResult != null && extractionResult.getStatus() == ResponseStatus.MAX_TOKENS
                                ? AssistantResponse.CompletionStatus.TRUNCATED_MAX_TOKENS
                                : AssistantResponse.CompletionStatus.COMPLETE;
                AssistantResponse assistantResponse = new AssistantResponse(
                        responseText, getType(), getModel(), completionStatus);

                // Complete the future with the AssistantResponse
                future.complete(assistantResponse);
            } catch (Exception e) {
                // Check if this is a cancellation exception, which is expected behavior
                if (e instanceof CancellationException ||
                        (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                    Log.d(TAG, "Gemini chat session was cancelled - this is expected behavior");
                    if (!future.isCancelled()) {
                        future.completeExceptionally(e);
                    }
                    return;
                }

                Log.e(TAG, "Error in chat session", e);
                if (!future.isCancelled()) {
                    future.completeExceptionally(new RuntimeException("Failed to get response from Gemini", e));
                }
            } finally {
                // Clear the current request reference
                currentRequest.compareAndSet(future, null);
            }
        });

        return future;
    }

    @Override
    public boolean clearApiKey() {
        try {
            apiKeyManager.clearApiKey(LLMClientFactory.LLMType.GEMINI);
            currentModel = null;
            // Clear the model cache since different API keys might have different model
            // access
            clearModelCache();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing Gemini API key", e);
            return false;
        }
    }

    @Override
    public boolean cancelCurrentRequest() {
        CancellableCompletableFuture<AssistantResponse> request = currentRequest.get();
        if (request != null && !request.isCancelledOrCompleted()) {
            Log.d(TAG, "Cancelling current Gemini API request");
            boolean cancelled = request.cancel(true);
            currentRequest.set(null);
            return cancelled;
        }
        return false;
    }

    // Helper method to call the Gemini API with message history
    private ExtractionResult callGeminiAPI(List<Message> history, String systemPrompt,
            CancellableCompletableFuture<AssistantResponse> future) {
        int retryCount = 0;
        Exception lastException = null;
        int currentTokenLimit = getMaxOutputTokens(currentModel);

        while (retryCount < MAX_RETRIES) {
            try {
                Log.d(TAG, "=== Calling Gemini API (attempt " + (retryCount + 1) + "/" + MAX_RETRIES
                        + ") with token limit: " + currentTokenLimit + " ===");
                ExtractionResult extractionResult = performGeminiAPICall(history, systemPrompt,
                        currentTokenLimit, future);

                // Check if the response indicates a token limit issue by parsing it again
                if (extractionResult.getStatus() == ResponseStatus.MAX_TOKENS) {
                    Log.w(TAG, "Token limit hit, reducing token limit and retrying");
                    currentTokenLimit = Math.max(currentTokenLimit / 2, 1024); // Reduce by half, minimum 1024
                    retryCount++;
                    if (retryCount < MAX_RETRIES) {
                        Log.d(TAG, "Retrying with reduced token limit: " + currentTokenLimit + " (attempt "
                                + (retryCount + 1) + "/" + MAX_RETRIES + ")");
                        continue;
                    } else {
                        Log.e(TAG, "All retry attempts exhausted. Final token limit: " + currentTokenLimit);
                    }
                }

                return extractionResult;
            } catch (java.io.IOException e) {
                lastException = e;
                retryCount++;

                // Check if this is a retryable network error
                boolean isRetryable = isRetryableException(e);
                String errorType = getExceptionType(e);

                Log.w(TAG, errorType + " on attempt " + retryCount + "/" + MAX_RETRIES + ": " + e.getMessage());

                if (isRetryable && retryCount < MAX_RETRIES) {
                    try {
                        Thread.sleep(RETRY_DELAY_MS);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted during retry delay", ie);
                    }
                } else if (!isRetryable) {
                    // For non-retryable errors, don't retry
                    Log.e(TAG, "Non-retryable error calling Gemini API", e);
                    throw new RuntimeException("Failed to call Gemini API", e);
                }
            } catch (Exception e) {
                // Check if this is a cancellation exception, which is expected behavior
                if (e instanceof CancellationException ||
                        (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                    Log.d(TAG, "Gemini API call was cancelled - this is expected behavior");
                    throw e; // Re-throw cancellation exceptions without wrapping
                }

                // For non-network errors, don't retry
                Log.e(TAG, "Non-retryable error calling Gemini API", e);
                throw new RuntimeException("Failed to call Gemini API", e);
            }
        }

        // If we get here, all retries failed
        Log.e(TAG, "All " + MAX_RETRIES + " attempts to call Gemini API failed");
        throw new RuntimeException("Failed to call Gemini API after " + MAX_RETRIES + " attempts", lastException);
    }

    // Helper method to determine if an exception is retryable
    private boolean isRetryableException(Exception e) {
        return e instanceof java.net.SocketTimeoutException ||
                e instanceof java.net.SocketException ||
                e instanceof java.net.ConnectException ||
                e instanceof java.net.UnknownHostException ||
                (e instanceof java.io.IOException && e.getMessage() != null &&
                        (e.getMessage().contains("timeout") || e.getMessage().contains("connection")));
    }

    // Helper method to get a human-readable exception type
    private String getExceptionType(Exception e) {
        if (e instanceof java.net.SocketTimeoutException) {
            return "Timeout";
        } else if (e instanceof java.net.SocketException) {
            return "Socket error";
        } else if (e instanceof java.net.ConnectException) {
            return "Connection error";
        } else if (e instanceof java.net.UnknownHostException) {
            return "Unknown host error";
        } else {
            return "Network error";
        }
    }

    // Actual API call implementation
    private ExtractionResult performGeminiAPICall(List<Message> history, String systemPrompt, int tokenLimit,
            CancellableCompletableFuture<AssistantResponse> future)
            throws java.io.IOException {
        try {
            // Manage conversation history to prevent context overflow
            List<Message> managedHistory = manageConversationHistory(history, systemPrompt);

            Log.d(TAG, "Message history size: " + managedHistory.size() + " (original: " + history.size() + ")");
            Log.d(TAG, "System prompt present: " + (systemPrompt != null));
            if (systemPrompt != null) {
                Log.d(TAG, "System prompt length: " + systemPrompt.length() + " characters");
                Log.d(TAG, "System prompt preview: " + systemPrompt.substring(0, Math.min(200, systemPrompt.length()))
                        + "...");
            }

            // Log all messages
            for (int i = 0; i < managedHistory.size(); i++) {
                Message msg = managedHistory.get(i);
                String contentPreview = msg.content.length() > 100 ? msg.content.substring(0, 100) + "..."
                        : msg.content;
                // Use the same role mapping as the API call for consistent logging
                String geminiRole;
                if (MessageRole.SYSTEM.equals(msg.role)) {
                    geminiRole = "system";
                } else if (MessageRole.USER.equals(msg.role)) {
                    geminiRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    geminiRole = "model";
                } else {
                    geminiRole = msg.role.getApiValue();
                }
                String imageInfo = "none";
                String mimeInfo = "none";
                if (msg.role == LLMClient.MessageRole.USER && ((LLMClient.UserMessage) msg).hasImages()) {
                    LLMClient.UserMessage userMsg = (LLMClient.UserMessage) msg;
                    List<File> validImages = userMsg.getValidImageFiles();
                    List<String> validMimes = userMsg.getValidMimeTypes();
                    if (!validImages.isEmpty()) {
                        imageInfo = validImages.size() + " images: " + validImages.get(0).getPath() +
                                (validImages.size() > 1 ? " and " + (validImages.size() - 1) + " more" : "");
                        mimeInfo = String.join(", ", validMimes);
                    }
                }
                Log.d(TAG, String.format("Message %d - Role: %s\nContent:\n%s\nImages: %s (MIME: %s)",
                        i, geminiRole, contentPreview, imageInfo, mimeInfo));
            }

            String apiKey = apiKeyManager.getApiKey(LLMClientFactory.LLMType.GEMINI);
            if (apiKey == null) {
                throw new RuntimeException("No Gemini API key configured");
            }

            ensureModelIsSet();

            // Check for cancellation before setting up connection
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before setting up connection");
                throw new CancellationException("Request was cancelled");
            }

            URL url = new URL(API_BASE_URL + "/models/" + currentModel + ":generateContent?key=" + apiKey);
            Log.d(TAG, "Calling Gemini API with URL: " + url.toString().replace(apiKey, "***API_KEY***"));
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            // Create the API request
            JSONObject requestBody = new JSONObject();

            // Add system instruction if present - ensure it's always included
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                JSONObject systemInstruction = new JSONObject();
                systemInstruction.put("parts", new JSONArray().put(new JSONObject().put("text", systemPrompt)));
                requestBody.put("systemInstruction", systemInstruction);
                Log.d(TAG, "Added system instruction to request");
            } else {
                Log.w(TAG, "No system prompt provided - this may cause the model to ignore important instructions");
            }

            // Build the contents array from history
            JSONArray contents = new JSONArray();

            // Add all messages from history
            for (Message message : managedHistory) {
                JSONObject messageObj = new JSONObject();
                // Skip the "system" message since it gets included with the
                // systemInstruction parameter above.
                if (!MessageRole.SYSTEM.equals(message.role)) {
                    // Gemini API expects "user" and "model" roles
                    String geminiRole;
                    if (MessageRole.USER.equals(message.role)) {
                        geminiRole = "user";
                    } else if (MessageRole.ASSISTANT.equals(message.role)) {
                        geminiRole = "model";
                    } else {
                        // Fallback for any other roles
                        geminiRole = message.role.getApiValue();
                        Log.w(TAG, "Unknown role for Gemini API: " + message.role + ", using: " + geminiRole);
                    }
                    messageObj.put("role", geminiRole);

                    JSONArray parts = new JSONArray();

                    // Add text part if not empty
                    if (message.content != null && !message.content.trim().isEmpty()) {
                        JSONObject textPart = new JSONObject();
                        textPart.put("text", message.content);
                        parts.put(textPart);
                    }

                    // Add image parts if images exist and are readable
                    if (message.role == LLMClient.MessageRole.USER && ((LLMClient.UserMessage) message).hasImages()) {
                        LLMClient.UserMessage userMsg = (LLMClient.UserMessage) message;
                        List<File> validImages = userMsg.getValidImageFiles();
                        List<String> validMimes = userMsg.getValidMimeTypes();

                        for (int imgIndex = 0; imgIndex < validImages.size()
                                && imgIndex < validMimes.size(); imgIndex++) {
                            try {
                                File imageFile = validImages.get(imgIndex);
                                String mimeType = validMimes.get(imgIndex);
                                String base64Image = encodeImageToBase64(imageFile);
                                JSONObject imagePart = new JSONObject();
                                JSONObject inlineData = new JSONObject();
                                inlineData.put("mime_type", mimeType);
                                inlineData.put("data", base64Image);
                                imagePart.put("inline_data", inlineData);
                                parts.put(imagePart);

                                Log.d(TAG, "Added image " + (imgIndex + 1) + "/" + validImages.size() +
                                        ", text length: " + message.content.length() +
                                        ", image base64 length: " + base64Image.length() +
                                        ", MIME type: " + mimeType);
                            } catch (IOException e) {
                                Log.e(TAG, "Failed to encode image " + (imgIndex + 1) + " for message", e);
                                // Continue with next image
                            }
                        }
                    }

                    messageObj.put("parts", parts);
                    contents.put(messageObj);
                }
            }

            requestBody.put("contents", contents);

            // Add generation config with more conservative settings for better system
            // prompt adherence
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.3); // Lower temperature for more consistent adherence to instructions

            // Use a more conservative token limit to avoid MAX_TOKENS truncation
            generationConfig.put("maxOutputTokens", tokenLimit);

            generationConfig.put("topP", 0.8); // Add top_p for better quality
            generationConfig.put("topK", 40); // Add top_k for better quality
            requestBody.put("generationConfig", generationConfig);

            // Write the request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            // Check for cancellation after writing request
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled after writing request");
                throw new CancellationException("Request was cancelled");
            }

            Log.d(TAG, "\n" +
                    "╔══════════════════════════╗\n" +
                    "║ START GEMINI API REQUEST ║\n" +
                    "╚══════════════════════════╝");
            String requestStr = requestBody.toString();

            // Pretty print the JSON for better readability in logs
            try {
                // Convert the JSON string to a pretty-printed version with 2-space indentation
                String prettyJson = formatJson(requestStr);

                // Log each line separately for better readability in logcat
                String[] lines = prettyJson.split("\n");
                for (String line : lines) {
                    Log.d(TAG, line);
                }
            } catch (Exception e) {
                // Fall back to normal logging if pretty printing fails
                Log.d(TAG, requestStr);
                Log.w(TAG, "Failed to pretty print JSON: " + e.getMessage());
            }

            Log.d(TAG, "Request length: " + requestStr.length() + " characters (" +
                    String.format("%.2f", requestStr.length() / 1024.0) + " KB)\n" +
                    "╔═════════════════════════╗\n" +
                    "║ STOP GEMINI API REQUEST ║\n" +
                    "╚═════════════════════════╝");

            // Check for cancellation before getting response
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before getting response");
                throw new CancellationException("Request was cancelled");
            }

            int responseCode = conn.getResponseCode();
            Log.d(TAG, "Gemini API response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read the response
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    int lineCount = 0;
                    while ((responseLine = br.readLine()) != null) {
                        // Check for cancellation every 10 lines during response reading
                        if (++lineCount % 10 == 0 && future.isCancelled()) {
                            Log.d(TAG, "Request cancelled during response reading");
                            throw new CancellationException("Request was cancelled");
                        }
                        response.append(responseLine.trim());
                    }
                    Log.d(TAG, "Raw HTTP response length: " + response.length());
                    ExtractionResult extractionResult = extractTextFromResponse(response.toString());
                    String extractedResponse = extractionResult.isSuccess() ? extractionResult.getText()
                            : extractionResult.getErrorMessage();
                    Log.d(TAG, "=== Complete LLM Response ===\n" + formatResponseWithLineNumbers(extractedResponse));
                    return extractionResult;
                }
            } else {
                // Handle error response
                String errorResponse = "";
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    errorResponse = response.toString();
                }

                // Provide specific error messages based on response code
                String errorMessage;
                switch (responseCode) {
                    case 429:
                        errorMessage = "Rate limit exceeded. Please try again later.";
                        break;
                    case 400:
                        errorMessage = "Bad request. Check your API key and request format.";
                        break;
                    case 401:
                        errorMessage = "Unauthorized. Check your API key.";
                        break;
                    case 403:
                        errorMessage = "Forbidden. Check your API key permissions.";
                        break;
                    case 500:
                        errorMessage = "Internal server error. Please try again.";
                        break;
                    case 503:
                        errorMessage = "Service unavailable. Please try again later.";
                        break;
                    default:
                        errorMessage = "HTTP error: " + responseCode;
                }

                Log.e(TAG, "Gemini API error response: " + errorResponse);
                throw new RuntimeException("Gemini API error: " + errorMessage + " (HTTP " + responseCode + ")");
            }
        } catch (java.io.IOException e) {
            // Re-throw IOException to allow retry logic to handle it
            throw e;
        } catch (Exception e) {
            // Check if this is a cancellation exception, which is expected behavior
            if (e instanceof CancellationException ||
                    (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                Log.d(TAG, "Gemini API call was cancelled during execution - this is expected behavior");
                throw new RuntimeException("Request was cancelled", e); // Wrap to avoid JSONException issues
            }

            Log.e(TAG, "Error calling Gemini API", e);
            throw new RuntimeException("Failed to call Gemini API", e);
        }
    }

    // Simple method to get text from Gemini response
    private ExtractionResult extractTextFromResponse(String jsonResponse) {
        try {
            Log.d(TAG, "Raw Gemini response: " + jsonResponse);

            JSONObject json = new JSONObject(jsonResponse);

            // Check if this is an error response
            if (json.has("error")) {
                JSONObject error = json.getJSONObject("error");
                String errorMessage = error.optString("message", "Unknown error");
                Log.e(TAG, "Gemini API returned error: " + errorMessage);
                return new ExtractionResult(ResponseStatus.API_ERROR, null, jsonResponse);
            }

            // Check if candidates exist
            if (!json.has("candidates")) {
                Log.e(TAG, "No candidates field in response");
                return new ExtractionResult(ResponseStatus.NO_CANDIDATES, null, jsonResponse);
            }

            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() == 0) {
                Log.e(TAG, "Empty candidates array");
                return new ExtractionResult(ResponseStatus.EMPTY_CANDIDATES, null, jsonResponse);
            }

            JSONObject firstCandidate = candidates.getJSONObject(0);

            boolean maxTokens = false;

            // Check for finish reason - if MAX_TOKENS, the response was truncated
            if (firstCandidate.has("finishReason")) {
                String finishReason = firstCandidate.getString("finishReason");
                if ("MAX_TOKENS".equals(finishReason)) {
                    Log.w(TAG, "Response truncated due to MAX_TOKENS limit");
                    maxTokens = true;
                } else if ("SAFETY".equals(finishReason)) {
                    Log.w(TAG, "Response blocked due to safety filters");
                    return new ExtractionResult(ResponseStatus.SAFETY_BLOCKED, null, jsonResponse);
                } else if ("RECITATION".equals(finishReason)) {
                    Log.w(TAG, "Response blocked due to recitation detection");
                    return new ExtractionResult(ResponseStatus.RECITATION_BLOCKED, null, jsonResponse);
                }
            }

            // Check if content exists
            if (!firstCandidate.has("content")) {
                Log.e(TAG, "No content field in candidate");
                return new ExtractionResult(ResponseStatus.NO_CONTENT, null, jsonResponse);
            }

            JSONObject content = firstCandidate.getJSONObject("content");

            // Try different possible response structures
            String text = null;

            // Structure 1: content.parts[].text (standard structure)
            if (content.has("parts")) {
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    JSONObject firstPart = parts.getJSONObject(0);
                    if (firstPart.has("text")) {
                        text = firstPart.getString("text");
                        Log.d(TAG, "Extracted text using parts structure");
                    }
                }
            }

            // Structure 2: content.text (direct text field)
            if (text == null && content.has("text")) {
                text = content.getString("text");
                Log.d(TAG, "Extracted text using direct text field");
            }

            // Structure 3: content.parts[].inlineData.text (for multimodal responses)
            if (text == null && content.has("parts")) {
                JSONArray parts = content.getJSONArray("parts");
                for (int i = 0; i < parts.length(); i++) {
                    JSONObject part = parts.getJSONObject(i);
                    if (part.has("inlineData") && part.getJSONObject("inlineData").has("text")) {
                        text = part.getJSONObject("inlineData").getString("text");
                        Log.d(TAG, "Extracted text using inlineData structure");
                        break;
                    }
                }
            }

            if (text != null) {
                return new ExtractionResult(maxTokens ?
                        ResponseStatus.MAX_TOKENS : ResponseStatus.SUCCESS,
                        text, jsonResponse);
            } else {
                Log.e(TAG, "Could not extract text from any known response structure");
                Log.e(TAG, "Content structure: " + content.toString());
                return new ExtractionResult(maxTokens ?
                        ResponseStatus.MAX_TOKENS : ResponseStatus.PARSE_ERROR,
                        null, jsonResponse);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from Gemini response", e);
            Log.e(TAG, "Response was: " + jsonResponse);
            return new ExtractionResult(ResponseStatus.UNKNOWN_ERROR, null, jsonResponse);
        }
    }

    /**
     * Formats text with line numbers in the format: "001: line content"
     * Each line number is three digits, right-aligned, followed by a colon and
     * space
     */
    private String formatResponseWithLineNumbers(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }

        String[] lines = text.split("\n");
        StringBuilder formatted = new StringBuilder();

        for (int i = 0; i < lines.length; i++) {
            // Format line number as three digits with leading zeros
            String lineNumber = String.format("%03d", i + 1);
            formatted.append(lineNumber).append(": ").append(lines[i]).append("\n");
        }

        return formatted.toString();
    }

    public String getModel() {
        return currentModel;
    }

    @Override
    public LLMClientFactory.LLMType getType() {
        return LLMClientFactory.LLMType.GEMINI;
    }

    public void setModel(String model) {
        this.currentModel = model;
        Log.d(TAG, "Set Gemini model to: " + model);

        // Log model properties for debugging
        ModelProperties props = getModelProperties(model);
        if (props != null) {
            Log.d(TAG, "Model properties: " + props.toString());
        } else {
            Log.w(TAG, "Unknown model: " + model + " - using default properties");
        }
    }

    /**
     * Get a summary of the current model's capabilities
     *
     * @return A string describing the current model's properties
     */
    public String getCurrentModelSummary() {
        if (currentModel == null) {
            return "No model selected";
        }

        ModelProperties props = getModelProperties(currentModel);
        if (props == null) {
            return "Unknown model: " + currentModel;
        }

        return String.format("Model: %s (Status: %s, Multimodal: %s, Max Input: %d tokens, Max Output: %d tokens)",
                currentModel, props.status, props.isMultimodal ? "Yes" : "No",
                props.maxInputTokens, props.maxOutputTokens);
    }

    /**
     * Check if the current model supports multimodal input (images, etc.)
     *
     * @return true if the model supports multimodal input
     */
    public boolean isCurrentModelMultimodal() {
        return isMultimodal(currentModel);
    }

    /**
     * Get the status of the current model
     *
     * @return Status string for the current model
     */
    public String getCurrentModelStatus() {
        return getModelStatus(currentModel);
    }

    private void ensureModelIsSet() {
        if (currentModel == null) {
            List<String> availableModels = fetchAvailableModels(context);
            if (!availableModels.isEmpty()) {
                currentModel = availableModels.get(0);
                Log.d(TAG, "Using first available model: " + currentModel);
            } else {
                throw new IllegalStateException("No Gemini models available. Please set a model first.");
            }
        }
    }

    /**
     * Fetches available Gemini models from the API with caching
     */
    public static List<String> fetchAvailableModels(Context context) {
        if (cachedModels != null) {
            Log.d(TAG, "Returning cached Gemini models");
            return new ArrayList<>(cachedModels);
        }

        Log.d(TAG, "Fetching Gemini models from API (cache miss or expired)");
        List<String> models = new ArrayList<>();
        ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getApiKey(LLMClientFactory.LLMType.GEMINI);

        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "No Gemini API key available");
            return models;
        }

        try {
            URL url = new URL(API_BASE_URL + "/models?key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    JSONObject jsonResponse = new JSONObject(response.toString());
                    JSONArray modelsArray = jsonResponse.getJSONArray("models");

                    for (int i = 0; i < modelsArray.length(); i++) {
                        JSONObject model = modelsArray.getJSONObject(i);
                        String name = model.getString("name");
                        if (name.contains("gemini")) {
                            // Extract just the model name from the full path
                            String[] parts = name.split("/");
                            models.add(parts[parts.length - 1]);
                        }
                    }

                    // Sort models with "pro" models first
                    models.sort((a, b) -> {
                        if (a.contains("pro") && !b.contains("pro"))
                            return -1;
                        if (!a.contains("pro") && b.contains("pro"))
                            return 1;
                        return a.compareTo(b);
                    });

                    Log.d(TAG, "Successfully fetched Gemini models: " + models);
                }
            } else {
                // Handle error response
                String errorResponse = "";
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    errorResponse = response.toString();
                }
                Log.e(TAG, "Error getting models: " + errorResponse);
                Log.e(TAG, "Failed to fetch Gemini models. Response code: " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching Gemini models", e);
        }

        // If API query fails, fall back to common models
        if (models.isEmpty()) {
            // Throw an error
            throw new RuntimeException("No Gemini models available");
        }

        // Cache the successful result
        cachedModels = new ArrayList<>(models);
        Log.d(TAG, "Cached Gemini models for future use");

        return models;
    }

    /**
     * Clears the cached models (useful for testing or when API key changes)
     */
    public static void clearModelCache() {
        cachedModels = null;
        Log.d(TAG, "Cleared Gemini model cache");
    }

    /**
     * Formats a JSON string with proper indentation.
     *
     * @param jsonString The raw JSON string to format
     * @return A properly indented JSON string
     * @throws Exception If the JSON formatting fails
     */
    private String formatJson(String jsonString) throws Exception {
        // First character determines if it's an object or array
        jsonString = jsonString.trim();
        if (jsonString.startsWith("{")) {
            // It's a JSONObject
            JSONObject json = new JSONObject(jsonString);
            return json.toString(2); // 2 spaces for indentation
        } else if (jsonString.startsWith("[")) {
            // It's a JSONArray
            JSONArray json = new JSONArray(jsonString);
            return json.toString(2); // 2 spaces for indentation
        } else {
            // Not valid JSON structure, return as-is
            return jsonString;
        }
    }

    // Helper method to manage conversation history to prevent context overflow
    private List<Message> manageConversationHistory(List<Message> history, String systemPrompt) {
        // Estimate total context size
        int totalSize = systemPrompt != null ? systemPrompt.length() : 0;
        for (Message msg : history) {
            totalSize += msg.content.length();
        }

        Log.d(TAG, "Total estimated context size: " + totalSize + " characters");

        // Get model-specific input token limit and convert to character estimate
        // Rough estimate: 1 token ≈ 4 characters for English text
        int maxInputTokens = getMaxInputTokens(currentModel);
        int maxCharacters = maxInputTokens * 4; // Conservative estimate

        // Use 80% of the limit to be safe
        int safeCharacterLimit = (int) (maxCharacters * 0.8);

        Log.d(TAG, "Model: " + currentModel + ", Max input tokens: " + maxInputTokens +
                ", Safe character limit: " + safeCharacterLimit);

        // If context is getting too large, keep only recent messages
        if (totalSize > safeCharacterLimit) {
            Log.w(TAG, "Context size too large (" + totalSize + " chars), truncating conversation history. Limit: "
                    + safeCharacterLimit + " chars");

            // Keep system prompt and last few messages
            List<Message> truncatedHistory = new ArrayList<>();

            // Keep the last 4 messages (2 user + 2 model pairs)
            int startIndex = Math.max(0, history.size() - 4);
            for (int i = startIndex; i < history.size(); i++) {
                truncatedHistory.add(history.get(i));
            }

            Log.d(TAG, "Truncated history from " + history.size() + " to " + truncatedHistory.size() + " messages");
            return truncatedHistory;
        }

        return history;
    }
}
