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
    private static List<String> availableModels = null;
    private String currentModel = null;
    private ApiKeyManager apiKeyManager;
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    private static final int HTTP_TIMEOUT = 30000; // 30 seconds timeout

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

        public void addUserMessage(String content) {
            messageHistory.add(new Message("user", content));
        }

        public void addModelMessage(String content) {
            messageHistory.add(new Message("model", content));
        }

        @Override
        public CompletableFuture<String> sendMessage(String message) {
            // Add the message to history first
            addUserMessage(message);

            // Make the API call with the full history
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Call the API with the full context
                    String response = callGeminiAPI(message, messageHistory);

                    // Extract Clojure code from the response
                    String code = extractClojureCode(response);

                    // Save the original response to history
                    addModelMessage(response);

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
        public List<Message> getMessageHistory() {
            return messageHistory;
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
    public CompletableFuture<String> generateInitialCode(String description) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING INITIAL CODE           │\n" +
                "│            LLM REQUEST   1                │\n" +
                "└───────────────────────────────────────────┘");

        Log.d(TAG, "Generating initial code for description: " + description);

        // Get or create a chat session for this app
        ChatSession chatSession = getOrCreateSession(description);

        // Reset session to start fresh
        chatSession.reset();

        // Format the prompt
        String prompt = formatInitialPrompt(description);

        // Send through the chat session
        return chatSession.sendMessage(prompt);
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
        int iterationNum = (session.getMessageHistory().size() / 2) + 1;
        String formattedNum = String.format("%3d", iterationNum);

        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING NEXT ITERATION         │\n" +
                "│            LLM REQUEST " + formattedNum + "                │\n" +
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
        // Log.d(TAG, "=== Formatted Prompt ===\n" + prompt);

        // Send through the chat session
        return session.sendMessage(prompt);
    }

    // Helper method to call the Gemini API with message history
    private String callGeminiAPI(String prompt, List<Message> history) {
        try {
            Log.d(TAG, "=== Calling Gemini API ===");
            Log.d(TAG, "Message history size: " + history.size());
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                Log.d(TAG, String.format("Message %d - Role: %s\nContent:\n%s",
                        i, msg.role, msg.content));
            }

            String apiKey = apiKeyManager.getApiKey();
            if (apiKey == null) {
                throw new RuntimeException("No Gemini API key configured");
            }

            if (currentModel == null) {
                throw new RuntimeException("No Gemini model selected");
            }
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

    // Helper method for simple API calls without history
    private String callGeminiAPI(String prompt) {
        // Create a temporary session and send message
        List<Message> tempHistory = new ArrayList<>();
        tempHistory.add(new Message("user", prompt));
        return callGeminiAPI(prompt, tempHistory);
    }

    public void setModel(String model) {
        this.currentModel = model;
        Log.d(TAG, "Set Gemini model to: " + model);
    }

    public String getModel() {
        return currentModel;
    }

    public List<String> getAvailableModels() {
        if (availableModels != null) {
            return availableModels;
        }

        try {
            String apiKey = apiKeyManager.getApiKey();
            if (apiKey == null) {
                throw new RuntimeException("No Gemini API key configured");
            }

            URL url = new URL(API_BASE_URL + "/models?key=" + apiKey);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
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

                    // Parse the JSON response
                    JSONObject json = new JSONObject(response.toString());
                    JSONArray models = json.getJSONArray("models");
                    List<String> modelNames = new ArrayList<>();

                    for (int i = 0; i < models.length(); i++) {
                        JSONObject model = models.getJSONObject(i);
                        String name = model.getString("name");
                        // Only add Gemini models
                        if (name.contains("gemini")) {
                            // Extract just the model name from the full path
                            String[] parts = name.split("/");
                            modelNames.add(parts[parts.length - 1]);
                        }
                    }

                    Log.d(TAG, "Retrieved " + modelNames.size() + " Gemini models from API");
                    availableModels = modelNames;
                    return modelNames;
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
                throw new RuntimeException("Failed to get models: " + responseCode + " - " + errorResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching models", e);
            throw new RuntimeException("Failed to fetch models from Gemini API", e);
        }
    }

    private String extractClojureCode(String response) {
        Log.d(TAG, "=== Extracting Clojure code from response length: " + response.length() + " ===");

        if (response == null || response.isEmpty()) {
            Log.w(TAG, "Empty response received");
            return "";
        }

        // First try to find ```clojure
        String startTag = "```clojure";
        int startIndex = response.indexOf(startTag);

        // If not found, try just ```
        if (startIndex == -1) {
            startTag = "```";
            startIndex = response.indexOf(startTag);
        }

        if (startIndex != -1) {
            // Move past the start tag and any whitespace/newline
            int codeStart = startIndex + startTag.length();
            while (codeStart < response.length() &&
                    (response.charAt(codeStart) == ' ' ||
                            response.charAt(codeStart) == '\n' ||
                            response.charAt(codeStart) == '\r')) {
                codeStart++;
            }

            // Find the closing ```
            String endTag = "```";
            int endIndex = response.indexOf(endTag, codeStart);

            if (endIndex != -1) {
                String code = response.substring(codeStart, endIndex).trim();
                Log.d(TAG, "Found code block between markers. Length: " + code.length());
                return code;
            } else {
                Log.w(TAG, "Found start marker but no end marker");
            }
        } else {
            Log.w(TAG, "No code block markers found in response");
        }

        // If we couldn't extract code between markers, return original
        return response;
    }
}
