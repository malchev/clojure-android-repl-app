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

        String sessionId = UUID.randomUUID().toString();
        Log.d(TAG, "Creating new session: " + sessionId);

        // Format the prompt using the helper from LLMClient
        String prompt = formatInitialPrompt(description);

        // Create a new chat session
        List<Message> messages = createChatSession(sessionId);

        // Add the system message
        messages.add(new Message("system",
                "You are a helpful assistant that generates Clojure code. Always respond with Clojure code in a markdown code block."));

        // Add the user prompt
        messages.add(new Message("user", prompt));

        // Send the request to OpenAI API
        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = callOpenAIAPI(messages);
                // Extract the Clojure code from the response
                String extractedCode = extractClojureCode(response);
                Log.d(TAG, "Extracted Clojure code from response, length: " + extractedCode.length());

                // Save the model's full response (not just the extracted code)
                messages.add(new Message("assistant", response));

                return extractedCode;
            } catch (Exception e) {
                Log.e(TAG, "Error generating initial code", e);
                throw new RuntimeException("Failed to generate initial code", e);
            }
        });
    }

    @Override
    public CompletableFuture<String> generateNextIteration(String description, String currentCode, String logcat,
            File screenshot, String feedback) {
        ensureModelIsSet();
        Log.d(TAG, "┌───────────────────────────────────────────┐");
        Log.d(TAG, "│         GENERATING NEXT ITERATION         │");
        Log.d(TAG, "└───────────────────────────────────────────┘");

        // Get existing session or create new one
        String sessionId = "app-" + Math.abs(description.hashCode());
        List<Message> messages = getOrCreateSession(sessionId).getMessageHistory();

        // Format the iteration prompt
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback);

        // Add the prompt to the session
        messages.add(new Message("user", prompt));

        // Send the request to OpenAI API
        return CompletableFuture.supplyAsync(() -> {
            try {
                String response = callOpenAIAPI(messages);
                // Extract the Clojure code from the response
                String extractedCode = extractClojureCode(response);
                Log.d(TAG, "Extracted Clojure code from response, length: " + extractedCode.length());

                // Save the model's full response (not just the extracted code)
                messages.add(new Message("assistant", response));

                return extractedCode;
            } catch (Exception e) {
                Log.e(TAG, "Error generating next iteration", e);
                throw new RuntimeException("Failed to generate next iteration", e);
            }
        });
    }

    private List<Message> createChatSession(String sessionId) {
        List<Message> messages = new ArrayList<>();
        sessionMessages.put(sessionId, messages);
        return messages;
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
                msgObj.put("role", msg.role);
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