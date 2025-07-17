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
import java.io.File;
import java.util.UUID;
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;

public class OpenAIChatClient extends LLMClient {
    private static final String TAG = "OpenAIChatClient";
    private String modelName = null;
    private final Map<String, List<Message>> sessionMessages = new HashMap<>();

    // Static cache for available models
    private static List<String> cachedModels = null;

    public OpenAIChatClient(Context context) {
        super(context);
        Log.d(TAG, "Creating new OpenAIChatClient");
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

    private class OpenAIChatSession implements ChatSession {
        private final String sessionId;
        private final List<Message> messages;

        public OpenAIChatSession(String sessionId) {
            this.sessionId = sessionId;

            // Use existing messages or create new list
            if (sessionMessages.containsKey(sessionId)) {
                this.messages = sessionMessages.get(sessionId);
            } else {
                this.messages = new ArrayList<>();
                sessionMessages.put(sessionId, this.messages);
            }

            Log.d(TAG, "OpenAI chat session initialized: " + sessionId +
                    " with " + messages.size() + " messages");
        }

        @Override
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Queuing system prompt in session: " + sessionId);
            // Always queue system prompts with SYSTEM role
            messages.add(new Message(MessageRole.SYSTEM, content));
        }

        @Override
        public void queueUserMessage(String content) {
            Log.d(TAG, "Queuing user message in session: " + sessionId);
            messages.add(new Message(MessageRole.USER, content));
        }

        @Override
        public void queueUserMessageWithImage(String content, File imageFile) {
            Log.d(TAG, "Queuing user message with image in session: " + sessionId);
            // For OpenAI client, just queue as regular message (ignore image for now)
            messages.add(new Message(MessageRole.USER, content));
        }

        @Override
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in session: " + sessionId);
            messages.add(new Message(MessageRole.ASSISTANT, content));
        }

        @Override
        public CompletableFuture<String> sendMessages() {
            Log.d(TAG, "Sending " + messages.size() + " messages in session: " + sessionId);
            // Print the message types and the first 50 characters of the content
            for (Message msg : messages) {
                // Use the same role mapping as the API call for consistent logging
                String openaiRole;
                if (MessageRole.SYSTEM.equals(msg.role)) {
                    openaiRole = getSystemRoleForModel();
                } else if (MessageRole.USER.equals(msg.role)) {
                    openaiRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    openaiRole = "assistant";
                } else if (MessageRole.DEVELOPER.equals(msg.role)) {
                    openaiRole = "developer";
                } else {
                    openaiRole = msg.role.getApiValue();
                }
                Log.d(TAG, "Message type: " + openaiRole + ", content: " + msg.content);
            }

            return CompletableFuture.supplyAsync(() -> {
                try {
                    String response = callOpenAIAPI(messages);
                    Log.d(TAG, "=== FULL OPENAI RESPONSE ===\n" + response);

                    // Add assistant message to history
                    queueAssistantResponse(response);

                    return response;
                } catch (Exception e) {
                    Log.e(TAG, "Error in chat session", e);
                    throw new RuntimeException("Failed to get response from OpenAI", e);
                }
            });
        }

        @Override
        public void reset() {
            Log.d(TAG, "Resetting chat session: " + sessionId);
            messages.clear();
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public ChatSession getOrCreateSession(UUID sessionId) {
        // Use the UUID as the session ID
        String sessionIdStr = sessionId.toString();
        return new OpenAIChatSession(sessionIdStr);
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
    public CompletableFuture<String> generateInitialCode(UUID sessionId, String description) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING INITIAL CODE           │");
        Log.d(TAG, "└───────────────────────────────────────────┘");

        ChatSession session = preparePromptForInitialCode(sessionId, description);
        return session.sendMessages();
    }

    @Override
    public CompletableFuture<String> generateInitialCode(UUID sessionId, String description, String initialCode) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING INITIAL CODE           │");
        Log.d(TAG, "│       WITH EXISTING CODE AS BASE          │");
        Log.d(TAG, "└───────────────────────────────────────────┘");

        ChatSession session = preparePromptForInitialCode(sessionId, description, initialCode);
        return session.sendMessages();
    }

    @Override
    public CompletableFuture<String> generateNextIteration(UUID sessionId, String description, String currentCode,
            String logcat,
            File screenshot, String feedback, File image) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING NEXT ITERATION         │");
        Log.d(TAG, "└───────────────────────────────────────────┘");

        // Check if image is provided and model is multimodal
        if (image != null) {
            ModelProperties props = getModelProperties(getModel());
            if (props == null || !props.isMultimodal) {
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Image parameter provided but model is not multimodal"));
            }
        }

        // Get existing session for this app description
        ChatSession session = getOrCreateSession(sessionId);

        // Format the iteration prompt
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback, image != null);

        // Queue the user message (with image attachment if provided)
        session.queueUserMessageWithImage(prompt, image);
        return session.sendMessages();
    }

    private String callOpenAIAPI(List<Message> messages) {
        ensureModelIsSet();
        Log.d(TAG, "=== Calling OpenAI API with " + messages.size() + " messages ===");

        try {
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", modelName);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 4096);

            JSONArray messagesArray = new JSONArray();
            for (Message msg : messages) {
                JSONObject msgObj = new JSONObject();
                // OpenAI API expects "system", "user", "assistant", and "developer" roles
                String openaiRole;
                if (MessageRole.SYSTEM.equals(msg.role)) {
                    // For SYSTEM messages, determine the correct API role based on model
                    openaiRole = getSystemRoleForModel();
                } else if (MessageRole.USER.equals(msg.role)) {
                    openaiRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    openaiRole = "assistant";
                } else if (MessageRole.DEVELOPER.equals(msg.role)) {
                    openaiRole = "developer";
                } else {
                    // Fallback for any other roles
                    openaiRole = msg.role.getApiValue();
                    Log.w(TAG, "Unknown role for OpenAI API: " + msg.role + ", using: " + openaiRole);
                }
                msgObj.put("role", openaiRole);
                msgObj.put("content", msg.content);
                messagesArray.put(msgObj);
            }
            requestBody.put("messages", messagesArray);

            return callOpenAIAPI(requestBody.toString());
        } catch (Exception e) {
            Log.e(TAG, "Error preparing OpenAI API request", e);
            throw new RuntimeException("Failed to prepare OpenAI API request", e);
        }
    }

    private String callOpenAIAPI(String requestBody) {
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
            URL url = new URL("https://api.openai.com/v1/chat/completions");
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("POST");
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Authorization",
                    "Bearer " + ApiKeyManager.getInstance(context).getApiKey(LLMClientFactory.LLMType.OPENAI));
            connection.setDoOutput(true);

            try (OutputStream os = connection.getOutputStream()) {
                byte[] input = requestBody.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            int responseCode = connection.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
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
                        String content = message.getString("content");
                        return content;
                    }
                    return jsonResponse;
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
            Log.e(TAG, "Error calling OpenAI API", e);
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }
}
