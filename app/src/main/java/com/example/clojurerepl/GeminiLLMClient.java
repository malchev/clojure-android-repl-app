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
    private static final String API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta";
    private String currentModel = null;
    private ApiKeyManager apiKeyManager;
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    private static final int HTTP_TIMEOUT = 120000; // 120 seconds timeout (increased from 30)
    private static final int MAX_RETRIES = 1;
    private static final int RETRY_DELAY_MS = 2000; // 2 seconds between retries

    // Static cache for available models
    private static List<String> cachedModels = null;

    public GeminiLLMClient(Context context) {
        super(context);
        Log.d(TAG, "Creating new GeminiLLMClient");
        apiKeyManager = ApiKeyManager.getInstance(context);
    }

    // Class to manage chat session state
    private class GeminiChatSession implements ChatSession {
        private String sessionId;
        private List<Message> messageHistory = new ArrayList<>();
        private String systemPrompt = null;

        public GeminiChatSession(String sessionId) {
            this.sessionId = sessionId;
            Log.d(TAG, "Created new chat session: " + sessionId);
        }

        @Override
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Setting system prompt in session: " + sessionId);
            // Store system prompt separately since Gemini now supports it natively
            this.systemPrompt = content;
            Log.d(TAG, "System prompt set successfully, length: " + (content != null ? content.length() : 0)
                    + " characters");
        }

        @Override
        public void queueUserMessage(String content) {
            Log.d(TAG, "Queuing user message in session: " + sessionId);
            messageHistory.add(new Message("user", content));
        }

        @Override
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in session: " + sessionId);
            messageHistory.add(new Message("model", content));
        }

        @Override
        public CompletableFuture<String> sendMessages() {
            Log.d(TAG, "Sending " + messageHistory.size() + " messages in session: " + sessionId);
            Log.d(TAG, "System prompt available: " + (systemPrompt != null ? "yes" : "no"));

            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Call the API with the full context including system prompt
                    String response = callGeminiAPI(messageHistory, systemPrompt);

                    // Save the original response to history
                    queueAssistantResponse(response);

                    // Return the extracted code
                    return response;
                } catch (Exception e) {
                    Log.e(TAG, "Error in chat session", e);
                    throw new RuntimeException("Failed to get response from Gemini", e);
                }
            });
        }

        @Override
        public void reset() {
            Log.d(TAG, "Resetting chat session: " + sessionId);
            messageHistory.clear();
            systemPrompt = null;
            Log.d(TAG, "Reset chat session: " + sessionId);
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messageHistory);
        }

        // Helper method to check if system prompt is still available
        public boolean hasSystemPrompt() {
            return systemPrompt != null && !systemPrompt.trim().isEmpty();
        }

        // Helper method to get system prompt length for debugging
        public int getSystemPromptLength() {
            return systemPrompt != null ? systemPrompt.length() : 0;
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
                    Log.d(TAG, "Got response, length: " + response.length());
                    return response;
                });
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description, String initialCode) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING INITIAL CODE           │\n" +
                "│       WITH EXISTING CODE AS BASE          │\n" +
                "└───────────────────────────────────────────┘");

        Log.d(TAG, "Generating initial code for description: " + description +
                ", using initial code: " + (initialCode != null ? "yes" : "no"));

        // Use the overloaded method that accepts initial code
        ChatSession chatSession = preparePromptForInitialCode(description, initialCode);

        // Send all messages and get the response
        return chatSession.sendMessages()
                .thenApply(response -> {
                    Log.d(TAG, "Got response, length: " + response.length());
                    return response;
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
                    Log.d(TAG, "Got response response, length: " + response.length());
                    return response;
                });
    }

    // Helper method to call the Gemini API with message history
    private String callGeminiAPI(List<Message> history, String systemPrompt) {
        int retryCount = 0;
        Exception lastException = null;

        while (retryCount < MAX_RETRIES) {
            try {
                Log.d(TAG, "=== Calling Gemini API (attempt " + (retryCount + 1) + "/" + MAX_RETRIES + ") ===");
                return performGeminiAPICall(history, systemPrompt);
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
    private String performGeminiAPICall(List<Message> history, String systemPrompt) throws java.io.IOException {
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

            for (int i = 0; i < managedHistory.size(); i++) {
                Message msg = managedHistory.get(i);
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

            // Add each message from history
            for (Message message : managedHistory) {
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

            // Add generation config with more conservative settings for better system
            // prompt adherence
            JSONObject generationConfig = new JSONObject();
            generationConfig.put("temperature", 0.3); // Lower temperature for more consistent adherence to instructions
            generationConfig.put("maxOutputTokens", 8192);
            generationConfig.put("topP", 0.8); // Add top_p for better quality
            generationConfig.put("topK", 40); // Add top_k for better quality
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

        // If context is getting too large, keep only recent messages
        // Gemini 2.5 Flash has a context window of ~1M tokens, but we'll be
        // conservative
        if (totalSize > 500000) { // ~500K characters as a conservative limit
            Log.w(TAG, "Context size too large, truncating conversation history");

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
