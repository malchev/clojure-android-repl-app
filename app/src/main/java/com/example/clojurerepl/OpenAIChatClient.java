package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.io.File;
import com.example.clojurerepl.auth.ApiKeyManager;

public class OpenAIChatClient extends LLMClient {
    private static final String TAG = "OpenAIChatClient";
    private static final String API_BASE_URL = "https://api.openai.com/v1";
    private static final int HTTP_TIMEOUT = 30000; // 30 seconds timeout
    private String currentModel = "gpt-3.5-turbo";
    private ApiKeyManager apiKeyManager;
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    public OpenAIChatClient(Context context) {
        super(context);
        Log.d(TAG, "Creating new OpenAIChatClient");
        apiKeyManager = ApiKeyManager.getInstance(context);
    }

    private class OpenAIChatSession implements ChatSession {
        private String sessionId;
        private List<Message> messageHistory = new ArrayList<>();

        public OpenAIChatSession(String sessionId) {
            this.sessionId = sessionId;
            Log.d(TAG, "Created new OpenAI chat session: " + sessionId);
        }

        @Override
        public CompletableFuture<String> sendMessage(String message) {
            // Add the message to history first
            addUserMessage(message);

            // Make the API call with the full history
            return CompletableFuture.supplyAsync(() -> {
                try {
                    // Call the API with the full context
                    String response = callOpenAIAPI(messageHistory);

                    // Extract Clojure code from the response
                    String code = extractClojureCode(response);

                    // Save the original response to history
                    addModelMessage(response);

                    // Return the extracted code
                    return code;
                } catch (Exception e) {
                    Log.e(TAG, "Error in chat session", e);
                    throw new RuntimeException("Failed to get response from OpenAI", e);
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

        private void addUserMessage(String content) {
            messageHistory.add(new Message("user", content));
        }

        private void addModelMessage(String content) {
            messageHistory.add(new Message("assistant", content));
        }
    }

    @Override
    public ChatSession getOrCreateSession(String description) {
        String sessionId = "openai-" + Math.abs(description.hashCode());
        if (!chatSessions.containsKey(sessionId)) {
            chatSessions.put(sessionId, new OpenAIChatSession(sessionId));
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

        // Send through the chat session
        return session.sendMessage(prompt);
    }

    private String callOpenAIAPI(List<Message> history) {
        try {
            Log.d(TAG, "=== Calling OpenAI API ===");
            Log.d(TAG, "Message history size: " + history.size());
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                Log.d(TAG, String.format("Message %d - Role: %s\nContent:\n%s",
                        i, msg.role, msg.content));
            }

            String apiKey = apiKeyManager.getApiKey();
            if (apiKey == null) {
                throw new RuntimeException("No OpenAI API key configured");
            }

            URL url = new URL(API_BASE_URL + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            // Create the API request
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", currentModel);
            requestBody.put("temperature", 0.7);
            requestBody.put("max_tokens", 4096);

            // Build the messages array from history
            JSONArray messages = new JSONArray();
            for (Message message : history) {
                JSONObject messageObj = new JSONObject();
                messageObj.put("role", message.role);
                messageObj.put("content", message.content);
                messages.put(messageObj);
            }
            requestBody.put("messages", messages);

            // Write the request
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestBody.toString().getBytes("utf-8");
                os.write(input, 0, input.length);
            }

            Log.d(TAG, "\n" +
                    "╔══════════════════════════════════════════╗\n" +
                    "║ START OPENAI API REQUEST AAAAAAAAAAAAAA ║\n" +
                    "╚══════════════════════════════════════════╝");
            String requestStr = requestBody.toString();
            Log.d(TAG, requestStr);
            Log.d(TAG, "Request length: " + requestStr.length() + "\n" +
                    "╔══════════════════════════════════════════╗\n" +
                    "║ STOP OPENAI API REQUEST BBBBBBBBBBBBBBBB ║\n" +
                    "╚══════════════════════════════════════════╝");

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
                Log.e(TAG, "OpenAI API error response: " + errorResponse);
                throw new RuntimeException("OpenAI API error: " + responseCode + " - " + errorResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error calling OpenAI API", e);
            throw new RuntimeException("Failed to call OpenAI API", e);
        }
    }

    private String extractTextFromResponse(String jsonResponse) {
        try {
            JSONObject json = new JSONObject(jsonResponse);
            JSONArray choices = json.getJSONArray("choices");
            if (choices.length() > 0) {
                JSONObject firstChoice = choices.getJSONObject(0);
                JSONObject message = firstChoice.getJSONObject("message");
                return message.getString("content");
            }
            return "Failed to extract text from OpenAI response";
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from OpenAI response", e);
            return "Error: " + e.getMessage();
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

    public void setModel(String model) {
        this.currentModel = model;
        Log.d(TAG, "Set OpenAI model to: " + model);
    }

    public String getModel() {
        return currentModel;
    }
}