package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import com.example.clojurerepl.auth.ApiKeyManager;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.io.File;
import java.io.IOException;
import java.util.UUID;

/**
 * Implementation of LLMClient for OpenAI's API.
 * Based on API documentation: https://platform.openai.com/docs/api-reference
 *
 * Vision/image support is determined dynamically when models are fetched from
 * the OpenAI API. The system:
 * -- Checks the API response for explicit vision capabilities
 * -- Falls back to heuristics based on model name patterns (e.g., "vision",
 * "multimodal", "gpt-5", "gpt-4o")
 * -- Caches the results for performance
 *
 * Use supportsImages(String) getModelProperties(String) to check if a specific
 * model supports image attachments. The system automatically adapts to new
 * models as they become available through the API.
 */
public class OpenAIChatClient extends LLMClient {
    private static final String TAG = "OpenAIChatClient";
    private String modelName = null;

    // Track the current request for cancellation
    private final AtomicReference<CancellableCompletableFuture<AssistantResponse>> currentRequest = new AtomicReference<>();

    // Static cache for available models
    private static List<String> cachedModels = null;
    private static final Map<String, Boolean> MODEL_COMPLETION_TOKEN_SUPPORT = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> MODEL_TEMPERATURE_LIMITED = new ConcurrentHashMap<>();
    // Cache for model vision/image support capabilities
    private static final Map<String, Boolean> MODEL_VISION_SUPPORT = new ConcurrentHashMap<>();

    public OpenAIChatClient(Context context, ChatSession chatSession) {
        super(context, chatSession);
        Log.d(TAG, "Creating new OpenAIChatClient with " + chatSession.getMessages().size() + " messages.");
    }

    public void setModel(String modelName) {
        this.modelName = modelName;
    }

    public String getModel() {
        return modelName;
    }

    @Override
    public LLMClientFactory.LLMType getType() {
        return LLMClientFactory.LLMType.OPENAI;
    }

    private void ensureModelIsSet() {
        assert modelName != null;
    }

    /**
     * Determines the correct API role for system messages based on the current
     * model.
     * Older models use "system" role, newer models use "developer" role.
     * 
     * @return "system" for older models, "developer" for newer models
     */
    private String getSystemRoleForModel() {
        // List of old models that require 'system' role
        String[] oldModels = {
                "gpt-3.5-turbo",
                "gpt-3.5-turbo-16k",
                "gpt-4",
                "gpt-4-32k",
                "gpt-4-turbo",
                "gpt-4-0125-preview",
                "gpt-4-1106-preview",
                "gpt-4-vision-preview"
        };

        if (modelName != null) {
            for (String oldModel : oldModels) {
                if (modelName.startsWith(oldModel)) {
                    return "system";
                }
            }
        }

        // Default to "developer" for newer models
        return "developer";
    }

    /**
     * Fetches available OpenAI models from the API with caching
     */
    public static List<String> fetchAvailableModels(Context context) {
        // Check if we have a valid cached result
        if (cachedModels != null) {
            Log.d(TAG, "Returning cached OpenAI models");
            return new ArrayList<>(cachedModels);
        }

        Log.d(TAG, "Fetching OpenAI models from API (cache miss or expired)");
        List<String> models = new ArrayList<>();
        ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getApiKey(LLMClientFactory.LLMType.OPENAI);

        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "No OpenAI API key available");
            return models;
        }

        try {
            URL url = new URL("https://api.openai.com/v1/models");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Authorization", "Bearer " + apiKey);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setConnectTimeout(10000); // 10 seconds timeout
            connection.setReadTimeout(10000);

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                JSONObject jsonResponse = new JSONObject(response.toString());
                JSONArray data = jsonResponse.getJSONArray("data");

                for (int i = 0; i < data.length(); i++) {
                    JSONObject model = data.getJSONObject(i);
                    String modelId = model.getString("id");
                    // Only include GPT models
                    if (modelId.startsWith("gpt-")) {
                        models.add(modelId);

                        // Determine if model supports vision/images
                        boolean supportsVision = determineVisionSupport(model, modelId);
                        MODEL_VISION_SUPPORT.put(modelId, supportsVision);
                        Log.d(TAG, "Model " + modelId + " vision support: " + supportsVision);
                    }
                }

                // Sort models with GPT-4 first
                models.sort((a, b) -> {
                    if (a.startsWith("gpt-4") && !b.startsWith("gpt-4"))
                        return -1;
                    if (!a.startsWith("gpt-4") && b.startsWith("gpt-4"))
                        return 1;
                    return a.compareTo(b);
                });

                Log.d(TAG, "Successfully fetched OpenAI models: " + models);
            } else {
                Log.e(TAG, "Failed to fetch OpenAI models. Response code: " + responseCode);
                throw new IOException("API returned code " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching OpenAI models", e);
        }

        // If API query fails, throw an error
        if (models.isEmpty()) {
            throw new RuntimeException("Failed to fetch OpenAI models and no fallback models available");
        }

        // Cache the successful result
        cachedModels = new ArrayList<>(models);
        Log.d(TAG, "Cached OpenAI models for future use");

        return models;
    }

    /**
     * Clears the cached models (useful for testing or when API key changes)
     */
    public static void clearModelCache() {
        cachedModels = null;
        MODEL_VISION_SUPPORT.clear();
        Log.d(TAG, "Cleared OpenAI model cache");
    }

    /**
     * Determines if a model supports vision/image inputs based on API response and
     * heuristics
     *
     * @param model   The model JSON object from the API response
     * @param modelId The model ID string
     * @return true if the model supports vision, false otherwise
     */
    private static boolean determineVisionSupport(JSONObject model, String modelId) {
        // First, check if the API response explicitly indicates vision support
        try {
            // Check for capabilities field (if present in API response)
            if (model.has("capabilities")) {
                JSONObject capabilities = model.getJSONObject("capabilities");
                if (capabilities.has("vision") && capabilities.getBoolean("vision")) {
                    return true;
                }
            }

            // Check for vision in other possible fields
            if (model.has("permission")) {
                JSONArray permissions = model.getJSONArray("permission");
                for (int j = 0; j < permissions.length(); j++) {
                    JSONObject perm = permissions.getJSONObject(j);
                    if (perm.has("allow_create_engine") || perm.has("allow_sampling")) {
                        // Permission exists, but doesn't directly indicate vision
                    }
                }
            }
        } catch (Exception e) {
            Log.d(TAG, "Could not parse capabilities from API response for " + modelId + ", using heuristics");
        }

        // Fallback to heuristics based on model name patterns
        // Models that typically support vision:
        // - gpt-4o, gpt-4o-mini (multimodal models)
        // - gpt-5, gpt-5.* (latest models with vision)
        // - gpt-4-turbo with vision/preview indicators
        // - gpt-4-vision-preview
        // - Models with "vision", "multimodal", or "o" (omni) in name

        String lowerModelId = modelId.toLowerCase();

        // Check for explicit vision indicators
        if (lowerModelId.contains("vision") || lowerModelId.contains("multimodal")) {
            return true;
        }

        // GPT-5 series (latest models with vision)
        if (lowerModelId.startsWith("gpt-5")) {
            return true;
        }

        // GPT-4o series (omni/multimodal)
        if (lowerModelId.startsWith("gpt-4o")) {
            return true;
        }

        // GPT-4 Turbo with vision variants
        if (lowerModelId.contains("gpt-4-turbo")) {
            // Check for vision-related date variants or preview
            if (lowerModelId.contains("2024-04-09") ||
                    lowerModelId.contains("2024-11-20") ||
                    lowerModelId.contains("preview")) {
                return true;
            }
            // Default GPT-4 Turbo may or may not support vision, be conservative
            return false;
        }

        // Legacy GPT-4 Vision Preview
        if (lowerModelId.contains("gpt-4-vision")) {
            return true;
        }

        // Default: assume no vision support for unknown models
        return false;
    }

    /**
     * Get model properties for a specific model
     * 
     * @param modelName The name of the model
     * @return ModelProperties for the model, or null if not found
     */
    public static ModelProperties getModelProperties(String modelName) {
        if (modelName == null) {
            return null;
        }

        // First, check if we have cached vision support from API
        Boolean cachedVisionSupport = MODEL_VISION_SUPPORT.get(modelName);
        boolean supportsVision = false;

        if (cachedVisionSupport != null) {
            // Use cached value from API
            supportsVision = cachedVisionSupport;
            Log.d(TAG, "Using cached vision support for " + modelName + ": " + supportsVision);
        } else {
            // Fallback to heuristics if not in cache (e.g., model not yet fetched from API)
            supportsVision = determineVisionSupportFromName(modelName);
            Log.d(TAG, "Using heuristic vision support for " + modelName + ": " + supportsVision);
        }

        // Determine context window and output limits based on model family
        int maxInputTokens = 8192; // Default
        int maxOutputTokens = 4096; // Default
        String status = "Current";

        // GPT-5 series
        if (modelName.startsWith("gpt-5")) {
            maxInputTokens = 128000;
            maxOutputTokens = 16384;
            status = "Current";
        }
        // GPT-4o series
        else if (modelName.startsWith("gpt-4o")) {
            maxInputTokens = 128000;
            maxOutputTokens = 16384;
            status = "Current";
        }
        // GPT-4 Turbo series
        else if (modelName.contains("gpt-4-turbo")) {
            maxInputTokens = 128000;
            maxOutputTokens = 4096;
            status = "Current";
        }
        // GPT-4 original
        else if (modelName.startsWith("gpt-4") && !modelName.contains("turbo") &&
                !modelName.contains("vision") && !modelName.contains("o")) {
            maxInputTokens = 8192;
            maxOutputTokens = 4096;
            status = "Legacy";
        }
        // GPT-3.5 Turbo
        else if (modelName.contains("gpt-3.5-turbo")) {
            maxInputTokens = 16385;
            maxOutputTokens = 4096;
            status = "Current";
        }
        // Unknown model - use defaults
        else {
            Log.d(TAG, "Unknown OpenAI model family: " + modelName + ", using default token limits");
        }

        return new ModelProperties(
                maxInputTokens,
                maxOutputTokens,
                supportsVision, // Use dynamically determined vision support
                status);
    }

    /**
     * Determines vision support from model name using heuristics
     * Used as fallback when model hasn't been fetched from API yet
     */
    private static boolean determineVisionSupportFromName(String modelName) {
        if (modelName == null) {
            return false;
        }

        String lowerModelName = modelName.toLowerCase();

        // Explicit vision indicators
        if (lowerModelName.contains("vision") || lowerModelName.contains("multimodal")) {
            return true;
        }

        // GPT-5 series
        if (lowerModelName.startsWith("gpt-5")) {
            return true;
        }

        // GPT-4o series (omni/multimodal)
        if (lowerModelName.startsWith("gpt-4o")) {
            return true;
        }

        // GPT-4 Turbo with vision variants
        if (lowerModelName.contains("gpt-4-turbo")) {
            if (lowerModelName.contains("2024-04-09") ||
                    lowerModelName.contains("2024-11-20") ||
                    lowerModelName.contains("preview")) {
                return true;
            }
            return false; // Conservative: standard GPT-4 Turbo may not support vision
        }

        // Legacy GPT-4 Vision Preview
        if (lowerModelName.contains("gpt-4-vision")) {
            return true;
        }

        // Default: no vision support
        return false;
    }

    /**
     * Check if a model supports image attachments
     *
     * @param modelName The name of the model
     * @return true if the model supports images, false otherwise
     */
    public static boolean supportsImages(String modelName) {
        ModelProperties props = getModelProperties(modelName);
        return props != null && props.isMultimodal;
    }

    @Override
    protected CancellableCompletableFuture<AssistantResponse> sendMessages(ChatSession session,
            MessageFilter messageFilter) {
        Log.d(TAG, "Sending " + session.getMessages().size() + " messages in session: " + session.getSessionId());

        // Cancel any existing request
        cancelCurrentRequest();

        // Filter messages if filter is provided
        final List<Message> messagesToSend = filterMessages(session, messageFilter);

        // Print the message types and the first 50 characters of the content
        for (Message msg : messagesToSend) {
            // Use the same role mapping as the API call for consistent logging
            String openaiRole;
            if (MessageRole.SYSTEM.equals(msg.role)) {
                openaiRole = getSystemRoleForModel();
            } else if (MessageRole.USER.equals(msg.role)) {
                openaiRole = "user";
            } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                openaiRole = "assistant";
            } else if (MessageRole.SYSTEM.equals(msg.role)) {
                openaiRole = getSystemRoleForModel();
            } else {
                throw new RuntimeException("Unknown role value " + msg.role.getApiValue());
            }
            Log.d(TAG, "Message type: " + openaiRole);
            String contentPreview = msg.content != null && msg.content.length() > 100
                    ? msg.content.substring(0, 100) + "..."
                    : msg.content;
            Log.d(TAG, "Message type: " + openaiRole + ", content: " + contentPreview);
        }

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

                OpenAICompletion completion = callOpenAIAPI(messagesToSend, future);

                // Check if cancelled after API call
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled after API call");
                    return;
                }

                String responsePreview = completion.content != null && completion.content.length() > 100
                        ? completion.content.substring(0, 100) + "..."
                        : completion.content;
                Log.d(TAG, "=== FULL OPENAI RESPONSE ===\n" + responsePreview);

                // Create AssistantResponse with model information
                AssistantResponse.CompletionStatus completionStatus = completion.truncated
                        ? AssistantResponse.CompletionStatus.TRUNCATED_MAX_TOKENS
                        : AssistantResponse.CompletionStatus.COMPLETE;
                AssistantResponse assistantResponse = new AssistantResponse(
                        completion.content, getType(), getModel(), completionStatus);

                future.complete(assistantResponse);
            } catch (Exception e) {
                // Check if this is a cancellation exception, which is expected behavior
                if (e instanceof CancellationException ||
                        (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                    Log.d(TAG, "OpenAI chat session was cancelled - this is expected behavior");
                    if (!future.isCancelled()) {
                        future.completeExceptionally(e);
                    }
                    return;
                }

                Log.e(TAG, "Error in chat session", e);
                if (!future.isCancelled()) {
                    future.completeExceptionally(new RuntimeException("Failed to get response from OpenAI", e));
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
            ApiKeyManager.getInstance(context).clearApiKey(LLMClientFactory.LLMType.OPENAI);
            modelName = null;
            // Clear the model cache since different API keys might have different model
            // access
            clearModelCache();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing OpenAI API key", e);
            return false;
        }
    }

    @Override
    public boolean cancelCurrentRequest() {
        CancellableCompletableFuture<AssistantResponse> request = currentRequest.get();
        if (request != null && !request.isCancelledOrCompleted()) {
            Log.d(TAG, "Cancelling current OpenAI API request");
            boolean cancelled = request.cancel(true);
            currentRequest.set(null);
            return cancelled;
        }
        return false;
    }

    private OpenAICompletion callOpenAIAPI(List<Message> messages,
            CancellableCompletableFuture<AssistantResponse> future) {
        boolean preferCompletionTokens = MODEL_COMPLETION_TOKEN_SUPPORT.getOrDefault(modelName, false);
        boolean omitTemperature = MODEL_TEMPERATURE_LIMITED.getOrDefault(modelName, false);
        RuntimeException lastException = null;

        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                return callOpenAIAPI(messages, future, preferCompletionTokens, omitTemperature);
            } catch (RuntimeException e) {
                lastException = e;
                if (!preferCompletionTokens && isUnsupportedMaxTokensError(e)) {
                    Log.w(TAG, "Model " + modelName
                            + " does not support max_tokens; retrying with max_completion_tokens");
                    preferCompletionTokens = true;
                    MODEL_COMPLETION_TOKEN_SUPPORT.put(modelName, true);
                    continue;
                }
                if (!omitTemperature && isUnsupportedTemperatureError(e)) {
                    Log.w(TAG, "Model " + modelName
                            + " does not allow custom temperature; retrying without temperature parameter");
                    omitTemperature = true;
                    MODEL_TEMPERATURE_LIMITED.put(modelName, true);
                    continue;
                }
                throw e;
            }
        }

        throw lastException != null ? lastException
                : new RuntimeException("Failed to call OpenAI API after retries");
    }

    private OpenAICompletion callOpenAIAPI(List<Message> messages,
            CancellableCompletableFuture<AssistantResponse> future,
            boolean useCompletionTokens,
            boolean omitTemperature) {
        ensureModelIsSet();
        Log.d(TAG, "=== Calling OpenAI API with " + messages.size() + " messages ===");

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", modelName);
            if (!omitTemperature) {
                requestBody.put("temperature", 0.7);
            }
            if (useCompletionTokens) {
                requestBody.put("max_completion_tokens", 4096);
            } else {
                requestBody.put("max_tokens", 4096);
            }

            // Check if any message has images (vision models may not support
            // response_format)
            boolean hasImages = false;
            for (Message msg : messages) {
                if (msg.role == MessageRole.USER && msg instanceof LLMClient.UserMessage) {
                    LLMClient.UserMessage userMsg = (LLMClient.UserMessage) msg;
                    if (userMsg.hasImages()) {
                        hasImages = true;
                        break;
                    }
                }
            }

            // Log image support status for debugging
            if (hasImages) {
                boolean modelSupportsImages = supportsImages(modelName);
                if (modelSupportsImages) {
                    Log.d(TAG, "Model " + modelName + " supports image attachments");
                } else {
                    Log.w(TAG, "Model " + modelName + " may not support image attachments. " +
                            "Vision support is determined dynamically from the API. " +
                            "Common vision-capable models include: gpt-5*, gpt-4o*, gpt-4-turbo (vision variants)");
                }
            }

            // Force JSON mode to prevent markdown code blocks (only if no images)
            // Vision models typically don't support response_format
            if (!hasImages) {
                JSONObject responseFormat = new JSONObject();
                responseFormat.put("type", "json_object");
                requestBody.put("response_format", responseFormat);
                Log.d(TAG, "Added response_format: json_object to enforce pure JSON output");
            } else {
                Log.d(TAG, "Skipping response_format for messages with images (vision models may not support it)");
            }

            JSONArray messagesArray = new JSONArray();
            for (Message msg : messages) {
                JSONObject msgObj = new JSONObject();
                // OpenAI API expects "system"/"developer", "user", and "assistant":
                String openaiRole;
                if (MessageRole.SYSTEM.equals(msg.role)) {
                    // For SYSTEM messages, determine the correct API role based on model
                    openaiRole = getSystemRoleForModel();
                } else if (MessageRole.USER.equals(msg.role)) {
                    openaiRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    openaiRole = "assistant";
                } else {
                    // Fallback for any other roles
                    openaiRole = msg.role.getApiValue();
                    throw new RuntimeException("Unknown role for OpenAI API: " + msg.role + ", using: " + openaiRole);
                }
                msgObj.put("role", openaiRole);

                // For OpenAI API, we need to handle both text and image content for user
                // messages
                boolean isUserMessage = msg instanceof LLMClient.UserMessage;
                if (msg.role == MessageRole.USER && isUserMessage && ((LLMClient.UserMessage) msg).hasImages()) {
                    // Create content array with text and multiple images
                    JSONArray contentArray = new JSONArray();

                    // Add text content if present
                    if (msg.content != null && !msg.content.trim().isEmpty()) {
                        JSONObject textContent = new JSONObject();
                        textContent.put("type", "text");
                        textContent.put("text", msg.content);
                        contentArray.put(textContent);
                    }

                    // Add all images
                    LLMClient.UserMessage userMsg = (LLMClient.UserMessage) msg;
                    List<File> validImages = userMsg.getValidImageFiles();
                    List<String> validMimes = userMsg.getValidMimeTypes();

                    for (int imgIndex = 0; imgIndex < validImages.size() && imgIndex < validMimes.size(); imgIndex++) {
                        try {
                            File imageFile = validImages.get(imgIndex);
                            String mimeType = validMimes.get(imgIndex);

                            // Verify image file exists and is readable
                            if (!imageFile.exists()) {
                                Log.e(TAG, "Image file " + (imgIndex + 1) + " does not exist: "
                                        + imageFile.getAbsolutePath());
                                continue; // Skip this image
                            }

                            if (!imageFile.canRead()) {
                                Log.e(TAG, "Image file " + (imgIndex + 1) + " is not readable: "
                                        + imageFile.getAbsolutePath());
                                continue; // Skip this image
                            }

                            Log.d(TAG, "Processing image " + (imgIndex + 1) + "/" + validImages.size() + ": " +
                                    imageFile.getAbsolutePath() + ", size: " + imageFile.length() + " bytes");

                            String base64Image = encodeImageToBase64(imageFile);
                            JSONObject imageContent = new JSONObject();
                            imageContent.put("type", "image_url");
                            JSONObject imageUrl = new JSONObject();
                            imageUrl.put("url", "data:" + mimeType + ";base64," + base64Image);
                            imageContent.put("image_url", imageUrl);
                            contentArray.put(imageContent);

                            Log.d(TAG, "Added image " + (imgIndex + 1) + "/" + validImages.size() +
                                    ", text length: " + (msg.content != null ? msg.content.length() : 0) +
                                    ", image base64 length: " + base64Image.length() +
                                    ", MIME type: " + mimeType);
                        } catch (IOException e) {
                            Log.e(TAG, "Failed to encode image " + (imgIndex + 1) + " for message: " +
                                    validImages.get(imgIndex).getAbsolutePath(), e);
                            // Continue with next image
                        } catch (Exception e) {
                            Log.e(TAG, "Unexpected error processing image " + (imgIndex + 1) + ": " +
                                    validImages.get(imgIndex).getAbsolutePath(), e);
                            // Continue with next image
                        }
                    }

                    msgObj.put("content", contentArray);
                } else {
                    // Regular text-only message
                    msgObj.put("content", msg.content);
                }

                messagesArray.put(msgObj);
            }
            requestBody.put("messages", messagesArray);

            return callOpenAIAPI(requestBody.toString(), future);
        } catch (Exception e) {
            // Check if this is a cancellation exception, which is expected behavior
            if (e instanceof CancellationException ||
                    (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                Log.d(TAG, "OpenAI API request preparation was cancelled - this is expected behavior");
                throw new RuntimeException("Request was cancelled", e); // Wrap to avoid JSONException issues
            }

            Log.e(TAG, "Error preparing OpenAI API request", e);
            throw new RuntimeException("Failed to prepare OpenAI API request", e);
        }
    }

    private OpenAICompletion callOpenAIAPI(String requestBody, CancellableCompletableFuture<AssistantResponse> future) {
        ensureModelIsSet();
        Log.d(TAG, "=== Calling OpenAI API ===");
        Log.d(TAG, "Request length: " + requestBody.length());
        Log.d(TAG, "╔══════════════════════════╗");
        Log.d(TAG, "║ START OPENAI API REQUEST ║");
        Log.d(TAG, "╚══════════════════════════╝");
        Log.d(TAG, requestBody);
        Log.d(TAG, "╔═════════════════════════╗");
        Log.d(TAG, "║ STOP OPENAI API REQUEST ║");
        Log.d(TAG, "╚═════════════════════════╝");

        try {
            // Check for cancellation before setting up connection
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before setting up connection");
                throw new CancellationException("Request was cancelled");
            }

            URL url = new URL("https://api.openai.com/v1/chat/completions");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization",
                    "Bearer " + ApiKeyManager.getInstance(context).getApiKey(LLMClientFactory.LLMType.OPENAI));
            connection.setDoOutput(true);

            // Check for cancellation before writing request
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before writing to connection");
                throw new CancellationException("Request was cancelled");
            }

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Check for cancellation after writing request
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled after writing request");
                throw new CancellationException("Request was cancelled");
            }

            // Check for cancellation before getting response
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before getting response");
                throw new CancellationException("Request was cancelled");
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    int lineCount = 0;
                    while ((responseLine = br.readLine()) != null) {
                        // Check for cancellation every 10 lines during response reading for efficiency
                        if (++lineCount % 10 == 0 && future.isCancelled()) {
                            Log.d(TAG, "Request cancelled during response reading");
                            throw new CancellationException("Request was cancelled");
                        }
                        response.append(responseLine.trim());
                    }
                    String jsonResponse = response.toString();
                    Log.d(TAG, "╔═══════════════════════════╗");
                    Log.d(TAG, "║ START OPENAI API RESPONSE ║");
                    Log.d(TAG, "╚═══════════════════════════╝");
                    String jsonResponsePreview = jsonResponse != null && jsonResponse.length() > 100
                            ? jsonResponse.substring(0, 100) + "..."
                            : jsonResponse;
                    Log.d(TAG, jsonResponsePreview);
                    Log.d(TAG, "╔══════════════════════════╗");
                    Log.d(TAG, "║ STOP OPENAI API RESPONSE ║");
                    Log.d(TAG, "╚══════════════════════════╝");

                    // Parse JSON to extract the content
                    JSONObject jsonObject = new JSONObject(jsonResponse);
                    JSONArray choices = jsonObject.getJSONArray("choices");
                    if (choices.length() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject message = choice.getJSONObject("message");
                        String content = message.optString("content", "");
                        String finishReason = choice.optString("finish_reason", "");
                        boolean truncated = "length".equalsIgnoreCase(finishReason);
                        if (truncated && (content == null || content.trim().isEmpty())) {
                            content = "Response was truncated due to token limit. Please continue.";
                        }
                        return new OpenAICompletion(content, truncated);
                    }
                    return new OpenAICompletion(jsonResponse, false);
                }
            } else {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    String errorResponse = response.toString();
                    Log.e(TAG, "OpenAI API error response: " + errorResponse);
                    throw new RuntimeException("OpenAI API error: " + responseCode + " - " + errorResponse);
                }
            }
        } catch (Exception e) {
            // Check if this is a cancellation exception, which is expected behavior
            if (e instanceof CancellationException ||
                    (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                Log.d(TAG, "OpenAI API call was cancelled - this is expected behavior");
                throw new RuntimeException("Request was cancelled", e); // Wrap to avoid IOException issues
            }

            Log.e(TAG, "Error calling OpenAI API", e);
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    private static final class OpenAICompletion {
        final String content;
        final boolean truncated;

        OpenAICompletion(String content, boolean truncated) {
            this.content = content;
            this.truncated = truncated;
        }
    }

    private boolean isUnsupportedMaxTokensError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Unsupported parameter")
                    && message.contains("'max_tokens'")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isUnsupportedTemperatureError(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            String message = current.getMessage();
            if (message != null && message.contains("Unsupported value")
                    && message.contains("'temperature'")) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
