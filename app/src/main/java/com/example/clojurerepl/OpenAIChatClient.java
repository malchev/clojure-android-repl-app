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

public class OpenAIChatClient extends LLMClient {
    private static final String TAG = "OpenAIChatClient";
    private String modelName = null;
    private final Map<String, List<Message>> sessionMessages = new HashMap<>();

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

    private void ensureModelIsSet() {
        if (modelName == null) {
            List<String> availableModels = LLMClientFactory.getAvailableModels(context,
                    LLMClientFactory.LLMType.OPENAI);
            if (!availableModels.isEmpty()) {
                modelName = availableModels.get(0);
                Log.d(TAG, "Using first available model: " + modelName);
            } else {
                throw new IllegalStateException("No OpenAI models available. Please set a model first.");
            }
        }
    }

    private class OpenAIChatSession implements ChatSession {
        private final String sessionId;
        private final List<Message> sessionMessages = new ArrayList<>();

        public OpenAIChatSession(String sessionId) {
            this.sessionId = sessionId;
        }

        @Override
        public CompletableFuture<String> sendMessage(String message) {
            Log.d(TAG, "Created new OpenAI chat session: " + sessionId);
            Log.d(TAG, "Reset chat session: " + sessionId);
            sessionMessages.clear();
            sessionMessages.add(new Message("user", message));

            return CompletableFuture.supplyAsync(() -> {
                try {
                    JSONObject requestBody = new JSONObject();
                    requestBody.put("model", modelName);
                    requestBody.put("temperature", 0.7);
                    requestBody.put("max_tokens", 4096);

                    JSONArray messagesArray = new JSONArray();
                    for (Message msg : sessionMessages) {
                        JSONObject msgObj = new JSONObject();
                        msgObj.put("role", msg.role);
                        msgObj.put("content", msg.content);
                        messagesArray.put(msgObj);
                    }
                    requestBody.put("messages", messagesArray);

                    String response = callOpenAIAPI(requestBody.toString());
                    JSONObject jsonResponse = new JSONObject(response);
                    JSONArray choices = jsonResponse.getJSONArray("choices");
                    if (choices.length() > 0) {
                        JSONObject choice = choices.getJSONObject(0);
                        JSONObject messageObj = choice.getJSONObject("message");
                        String content = messageObj.getString("content");
                        sessionMessages.add(new Message("assistant", content));
                        return content;
                    }
                    throw new RuntimeException("No response from OpenAI");
                } catch (Exception e) {
                    Log.e(TAG, "Error in chat session", e);
                    throw new RuntimeException("Failed to get response from OpenAI", e);
                }
            });
        }

        @Override
        public void reset() {
            sessionMessages.clear();
        }

        @Override
        public List<Message> getMessageHistory() {
            return new ArrayList<>(sessionMessages);
        }
    }

    @Override
    public ChatSession getOrCreateSession(String description) {
        String sessionId = "openai-" + System.currentTimeMillis();
        return new OpenAIChatSession(sessionId);
    }

    public ChatSession createChatSession() {
        return getOrCreateSession(null);
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING INITIAL CODE           │");
        Log.d(TAG, "│            LLM REQUEST   1                │");
        Log.d(TAG, "└───────────────────────────────────────────┘");
        Log.d(TAG, "Generating initial code for description: " + description);

        ChatSession session = createChatSession();
        String formattedPrompt = formatInitialPrompt(description);
        return session.sendMessage(formattedPrompt);
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING NEXT ITERATION         │");
        Log.d(TAG, "│            LLM REQUEST   2                │");
        Log.d(TAG, "└───────────────────────────────────────────┘");

        Log.d(TAG, "=== Starting Next Iteration ===");
        Log.d(TAG, "Description: " + description);
        Log.d(TAG, "Current code length: " + (currentCode != null ? currentCode.length() : 0));
        Log.d(TAG,
                "=== Logcat Content Being Sent === (" + (logcat != null ? logcat.split("\n").length : 0) + " lines)");
        Log.d(TAG, "Screenshot present: " + (screenshot != null ? screenshot.getPath() : "null"));
        Log.d(TAG, "Feedback: " + feedback);

        ChatSession session = createChatSession();
        String formattedPrompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback);
        return session.sendMessage(formattedPrompt);
    }

    private String callOpenAIAPI(String requestBody) {
        ensureModelIsSet();
        Log.d(TAG, "=== Calling OpenAI API ===");
        Log.d(TAG, "Request length: " + requestBody.length());
        Log.d(TAG, "╔══════════════════════════════════════════╗");
        Log.d(TAG, "║ START OPENAI API REQUEST AAAAAAAAAAAAAA ║");
        Log.d(TAG, "╚══════════════════════════════════════════╝");
        Log.d(TAG, requestBody);
        Log.d(TAG, "╔══════════════════════════════════════════╗");
        Log.d(TAG, "║ STOP OPENAI API REQUEST BBBBBBBBBBBBBBBB ║");
        Log.d(TAG, "╚══════════════════════════════════════════╝");

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
                    return response.toString();
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

    public String extractClojureCode(String response) {
        Log.d(TAG, "Extracting Clojure code from response of length: " + response.length());

        if (response == null || response.isEmpty()) {
            Log.w(TAG, "Empty response received");
            return "";
        }

        // Try to find the start of a Clojure code block
        int startIndex = response.indexOf("```clojure");
        if (startIndex == -1) {
            // If no Clojure-specific tag, try generic code block
            startIndex = response.indexOf("```");
            if (startIndex == -1) {
                Log.w(TAG, "No code block markers found in response");
                return response.trim(); // Return the whole response if no markers found
            }
            startIndex += 3; // Skip the opening ```
        } else {
            startIndex += 10; // Skip the opening ```clojure
        }

        // Find the end of the code block
        int endIndex = response.indexOf("```", startIndex);
        if (endIndex == -1) {
            Log.w(TAG, "No closing code block marker found");
            return response.substring(startIndex).trim();
        }

        String code = response.substring(startIndex, endIndex).trim();
        Log.d(TAG, "Extracted code block of length: " + code.length());
        return code;
    }
}