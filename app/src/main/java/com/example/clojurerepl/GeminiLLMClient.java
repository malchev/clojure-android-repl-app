package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.util.concurrent.CompletableFuture;
import java.util.List;
import java.util.ArrayList;
import java.net.HttpURLConnection;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;
import com.example.clojurerepl.auth.ApiKeyManager;

public class GeminiLLMClient extends LLMClient {
    private static final String TAG = "GeminiLLMClient";
    private static final String API_BASE_URL = "https://generativelanguage.googleapis.com/v1";
    private String currentModel = null;
    private ApiKeyManager apiKeyManager;
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    private static final int HTTP_TIMEOUT = 30000; // 30 seconds timeout
    // Define a prefix for system prompts converted to user messages
    private static final String SYSTEM_PROMPT_PREFIX = "[SYSTEM INSTRUCTION]: ";

    public GeminiLLMClient(Context context) {
        super(context);
        Log.d(TAG, "Creating new GeminiLLMClient");
        apiKeyManager = ApiKeyManager.getInstance(context);
    }

    // Class to manage chat session state
    private class GeminiChatSession implements ChatSession {
        private String sessionId;
        private List<Message> messageHistory = new ArrayList<>();

        public GeminiChatSession(String sessionId) {
            this.sessionId = sessionId;
            Log.d(TAG, "Created new chat session: " + sessionId);
        }

        @Override
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Queuing system prompt as user message with prefix in session: " + sessionId);
            // Gemini doesn't support system role, so convert it to a user message with a
            // prefix
            messageHistory.add(new Message("user", SYSTEM_PROMPT_PREFIX + content));
        }

        @Override
        public void queueUserMessage(String content) {
            Log.d(TAG, "Queuing user message in session: " + sessionId);
            messageHistory.add(new Message("user", content));
        }

        @Override
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in session: " + sessionId);
            messageHistory.add(new Message("assistant", content));
        }

        @Override
        public CompletableFuture<String> sendMessages() {
            Log.d(TAG, "Sending " + messageHistory.size() + " messages in session: " + sessionId);

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Call the API with the full context
                    String response = callGeminiAPI(messageHistory);

                    // Extract Clojure code from the response
                    String code = extractClojureCode(response);

                    // Save the original response to history
                    queueAssistantResponse(response);

                    // Return the extracted code
                    return code;
                } catch (Exception e) {
                    Log.e(TAG, "Error in chat session", e);
                    throw new RuntimeException("Failed to get response from Gemini", e);
                }
            });
        }

        @Override
        public void reset() {
            messageHistory.clear();
            Log.d(TAG, "Reset chat session: " + sessionId);
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messageHistory);
        }
    }

    @Override
    public ChatSession getOrCreateSession(String description) {
        String sessionId = "app-" + Math.abs(description.hashCode());
        if (!chatSessions.containsKey(sessionId)) {
            chatSessions.put(sessionId, new GeminiChatSession(sessionId));
        }
        return chatSessions.get(sessionId);
    }

    @Override
    public boolean clearApiKey() {
        try {
            apiKeyManager.clearApiKey(LLMClientFactory.LLMType.GEMINI);
            currentModel = null;
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing Gemini API key", e);
            return false;
        }
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING INITIAL CODE           │\n" +
                "│            LLM REQUEST   1                │\n" +
                "└───────────────────────────────────────────┘");

        Log.d(TAG, "Generating initial code for description: " + description);

        ChatSession chatSession = preparePromptForInitialCode(description);

        // Send all messages and get the response
        return chatSession.sendMessages()
                .thenApply(response -> {
                    String extractedCode = extractClojureCode(response);
                    Log.d(TAG, "Extracted Clojure code from response, length: " + extractedCode.length());
                    return extractedCode;
                });
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        // Get the iteration number from the chat session
        ChatSession session = getOrCreateSession(description);

        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING NEXT ITERATION         │\n" +
                "└───────────────────────────────────────────┘");

        Log.d(TAG, "=== Starting Next Iteration ===");
        Log.d(TAG, "Description: " + description);
        Log.d(TAG, "Current code length: " + (currentCode != null ? currentCode.length() : 0));
        Log.d(TAG,
                "=== Logcat Content Being Sent === (" + (logcat != null ? logcat.split("\n").length : 0) + " lines)");
        Log.d(TAG, "Screenshot present: " + (screenshot != null ? screenshot.getPath() : "null"));
        Log.d(TAG, "Feedback: " + feedback);

        // Format the iteration prompt
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback);

        // Queue the user message
        session.queueUserMessage(prompt);

        // Send all messages and get the response
        return session.sendMessages()
                .thenApply(response -> {
                    String extractedCode = extractClojureCode(response);
                    Log.d(TAG, "Extracted Clojure code from response, length: " + extractedCode.length());
                    return extractedCode;
                });
    }

    // Helper method to call the Gemini API with message history
    private String callGeminiAPI(List<Message> history) {
        try {
            Log.d(TAG, "=== Calling Gemini API ===");
            Log.d(TAG, "Message history size: " + history.size());
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                Log.d(TAG, String.format("Message %d - Role: %s\nContent:\n%s",
                        i, msg.role, msg.content.length() > 100 ? msg.content.substring(0, 100) + "..." : msg.content));
            }

            String apiKey = apiKeyManager.getApiKey(LLMClientFactory.LLMType.GEMINI);
            if (apiKey == null) {
                throw new RuntimeException("No Gemini API key configured");
            }

            ensureModelIsSet();

            URL url = new URL(API_BASE_URL + "/models/" + currentModel + ":generateContent?key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            // Create the API request
            JSONObject requestBody = new JSONObject();

            // Build the contents array from history
            JSONArray contents = new JSONArray();

            // Add each message from history
            for (Message message : history) {
                JSONObject messageObj = new JSONObject();
                messageObj.put("role", message.role);

                JSONArray parts = new JSONArray();
                JSONObject textPart = new JSONObject();
                textPart.put("text", message.content);
                parts.put(textPart);

                messageObj.put("parts", parts);
                contents.put(messageObj);
            }

            requestBody.put("contents", contents);

            // Add generation config
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.7);
            generationConfig.put("maxOutputTokens", 8192);
            requestBody.put("generationConfig", generationConfig);

            // Write the request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
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

            Log.d(TAG, "Request length: " + requestStr.length() + "\n" +
                    "╔═════════════════════════╗\n" +
                    "║ STOP GEMINI API REQUEST ║\n" +
                    "╚═════════════════════════╝");

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read the response
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    String extractedResponse = extractTextFromResponse(response.toString());
                    Log.d(TAG, "=== Complete LLM Response ===\n" + extractedResponse);
                    return extractedResponse;
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
                Log.e(TAG, "Gemini API error response: " + errorResponse);
                throw new RuntimeException("Gemini API error: " + responseCode + " - " + errorResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling Gemini API", e);
            throw new RuntimeException("Failed to call Gemini API", e);
        }
    }

    // Simple method to get text from Gemini response
    private String extractTextFromResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            JSONArray candidates = json.getJSONArray("candidates");
            if (candidates.length() > 0) {
                JSONObject firstCandidate = candidates.getJSONObject(0);
                JSONObject content = firstCandidate.getJSONObject("content");
                JSONArray parts = content.getJSONArray("parts");
                if (parts.length() > 0) {
                    return parts.getJSONObject(0).getString("text");
                }
            }
            return "Failed to extract text from Gemini response";
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from Gemini response", e);
            return "Error: " + e.getMessage();
        }
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
     * Fetches available Gemini models from the API
     */
    public static List<String> fetchAvailableModels(Context context) {
        Log.d(TAG, "Fetching Gemini models");
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

        return models;
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
}
