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
        Log.d(TAG, "Cleared OpenAI model cache");
    }

    /**
     * Get model properties for a specific model
     * 
     * @param modelName The name of the model
     * @return ModelProperties for the model, or null if not found
     */
    public static ModelProperties getModelProperties(String modelName) {
        // TODO: Implement OpenAI model properties lookup table
        return null;
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
            Log.d(TAG, "Message type: " + openaiRole + ", content: " + msg.content);
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

                Log.d(TAG, "=== FULL OPENAI RESPONSE ===\n" + completion.content);

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

            // Force JSON mode to prevent markdown code blocks
            JSONObject responseFormat = new JSONObject();
            responseFormat.put("type", "json_object");
            requestBody.put("response_format", responseFormat);
            Log.d(TAG, "Added response_format: json_object to enforce pure JSON output");

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
                msgObj.put("content", msg.content);
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
                    Log.d(TAG, jsonResponse);
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
