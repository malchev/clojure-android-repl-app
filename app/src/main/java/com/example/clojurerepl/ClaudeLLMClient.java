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
import java.util.Map;
import java.util.HashMap;
import java.io.IOException;

/**
 * Implementation of LLMClient for Anthropic's Claude API.
 * Based on API documentation: https://docs.anthropic.com/claude/reference/
 */
public class ClaudeLLMClient extends LLMClient {
    private static final String TAG = "ClaudeLLMClient";
    private static final String API_BASE_URL = "https://api.anthropic.com/v1";
    private static final String API_VERSION = "2023-06-01";
    private static final int HTTP_TIMEOUT = 60000; // 60 seconds timeout

    private String currentModel = null;
    private Map<String, ChatSession> chatSessions = new HashMap<>();

    // Static cache for available models
    private static List<String> cachedModels = null;

    public ClaudeLLMClient(Context context) {
        super(context);
        Log.d(TAG, "Creating new ClaudeLLMClient");
    }

    @Override
    public LLMClientFactory.LLMType getType() {
        return LLMClientFactory.LLMType.CLAUDE;
    }

    @Override
    public String getModel() {
        return currentModel;
    }

    public void setModel(String modelName) {
        this.currentModel = modelName;
        Log.d(TAG, "Set Claude model to: " + modelName);
    }

    private void ensureModelIsSet() {
        Log.d(TAG, "DEBUG: Checking if Claude model is set");
        assert currentModel != null;
    }

    // Message Storage Implementation
    class ClaudeChatSession implements ChatSession {
        private final String sessionId;
        private final List<Message> messages;

        public ClaudeChatSession(String sessionId) {
            this.sessionId = sessionId;
            this.messages = new ArrayList<>();

            Log.d(TAG, "New Claude chat session object created with ID: " + sessionId);
        }

        @Override
        public void reset() {
            Log.d(TAG, "Resetting chat session: " + sessionId);
            messages.clear();
        }

        @Override
        public void queueSystemPrompt(String content) {
            Log.d(TAG, "Queuing system prompt in session: " + sessionId);
            messages.add(new Message("system", content));
        }

        @Override
        public void queueUserMessage(String content) {
            Log.d(TAG, "Queuing user message in session: " + sessionId);
            messages.add(new Message("user", content));
        }

        @Override
        public void queueUserMessageWithImage(String content, File imageFile) {
            Log.d(TAG, "Queuing user message with image in session: " + sessionId);
            // For Claude client, just queue as regular message (ignore image for now)
            messages.add(new Message("user", content));
        }

        @Override
        public void queueAssistantResponse(String content) {
            Log.d(TAG, "Queuing assistant response in session: " + sessionId);
            messages.add(new Message("assistant", content));
        }

        @Override
        public CompletableFuture<String> sendMessages() {
            Log.d(TAG, "DEBUG: ClaudeChatSession.sendMessages called with " + messages.size() + " messages in session: "
                    + sessionId);
            // Log message types and short previews for debugging
            for (Message msg : messages) {
                String preview = msg.content.length() > 50 ? msg.content.substring(0, 50) + "..." : msg.content;
                Log.d(TAG, "DEBUG: Message type: " + msg.role + ", content: " + preview);
            }

            return CompletableFuture.supplyAsync(() -> {
                Thread.currentThread().setName("Claude-API-Thread");
                Log.d(TAG, "DEBUG: Inside CompletableFuture thread: " + Thread.currentThread().getName());
                try {
                    Log.d(TAG, "DEBUG: About to call Claude API in thread: " + Thread.currentThread().getName());
                    String response = null;
                    try {
                        response = callClaudeAPI(messages);
                        Log.d(TAG, "DEBUG: Claude API call completed successfully");
                    } catch (Exception e) {
                        Log.e(TAG, "ERROR: callClaudeAPI failed", e);
                        throw e;
                    }

                    Log.d(TAG, "=== FULL CLAUDE RESPONSE ===\n" +
                            (response != null
                                    ? (response.length() > 1000 ? response.substring(0, 1000) + "..." : response)
                                    : "NULL RESPONSE"));

                    // Add assistant message to history
                    queueAssistantResponse(response);

                    return response;
                } catch (Exception e) {
                    Log.e(TAG, "ERROR: Exception in sendMessages CompletableFuture", e);
                    throw new RuntimeException("ERROR: Exception in sendMessages CompletableFuture", e);
                }
            });
        }

        @Override
        public List<Message> getMessages() {
            return new ArrayList<>(messages);
        }
    }

    @Override
    public ChatSession getOrCreateSession(String description) {
        // Use a consistent session ID based on description hash code
        String sessionId = "claude-app-" + Math.abs(description.hashCode());

        // Create a new session if one doesn't exist for this ID
        if (!chatSessions.containsKey(sessionId)) {
            Log.d(TAG, "Creating new Claude chat session with ID: " + sessionId);
            chatSessions.put(sessionId, new ClaudeChatSession(sessionId));
            return chatSessions.get(sessionId);
        }

        // Use existing session
        Log.d(TAG, "Reusing existing Claude chat session with ID: " + sessionId);
        ClaudeChatSession existingSession = (ClaudeChatSession) chatSessions.get(sessionId);

        // Log session message count for debugging
        Log.d(TAG, "Existing session has " + existingSession.getMessages().size() + " messages");

        return existingSession;
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│ CLAUDE STARTING INITIAL CODE GENERATION   │\n" +
                "└───────────────────────────────────────────┘");

        try {
            ensureModelIsSet();

            // Add debug to trace code execution
            Log.d(TAG, "DEBUG: Starting generateInitialCode for Claude client with description: " +
                    (description != null
                            ? (description.length() > 50 ? description.substring(0, 50) + "..." : description)
                            : "null"));

            ChatSession session = preparePromptForInitialCode(description);
            Log.d(TAG, "DEBUG: Created chat session, about to send messages to Claude API");

            return session.sendMessages().handle((response, ex) -> {
                if (ex != null) {
                    Log.e(TAG, "ERROR: Failed in Claude sendMessages", ex);
                    return "ERROR: " + ex.getMessage();
                } else {
                    Log.d(TAG, "SUCCESS: Claude sendMessages completed successfully");
                    return response;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Exception in generateInitialCode", e);
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<String> generateInitialCode(String description, String initialCode) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING INITIAL CODE           │\n" +
                "│       WITH EXISTING CODE AS BASE          │\n" +
                "└───────────────────────────────────────────┘");

        try {
            ensureModelIsSet();

            // Add debug to trace code execution
            Log.d(TAG, "DEBUG: Starting generateInitialCode with template for description: " +
                    (description != null
                            ? (description.length() > 50 ? description.substring(0, 50) + "..." : description)
                            : "null"));

            ChatSession session = preparePromptForInitialCode(description, initialCode);
            Log.d(TAG, "DEBUG: Created chat session with template, about to send messages to Claude API");

            return session.sendMessages().handle((response, ex) -> {
                if (ex != null) {
                    Log.e(TAG, "ERROR: Failed in Claude sendMessages with template", ex);
                    return "ERROR: " + ex.getMessage();
                } else {
                    Log.d(TAG, "SUCCESS: Claude sendMessages with template completed successfully");
                    return response;
                }
            });
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Exception in generateInitialCode with template", e);
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CompletableFuture<String> generateNextIteration(
            String description,
            String currentCode,
            String logcat,
            File screenshot,
            String feedback,
            File image) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING NEXT ITERATION         │\n" +
                "└───────────────────────────────────────────┘");

        // Check if image is provided and model is multimodal
        if (image != null) {
            ModelProperties props = getModelProperties(getModel());
            if (props == null || !props.isMultimodal) {
                return CompletableFuture.failedFuture(
                        new UnsupportedOperationException("Image parameter provided but model is not multimodal"));
            }
        }

        // Get the existing session without resetting it
        ChatSession session = getOrCreateSession(description);

        Log.d(TAG, "Starting generateNextIteration with session containing " +
                ((ClaudeChatSession) session).getMessages().size() + " messages");

        // Add logging of the current message types
        List<Message> messages = ((ClaudeChatSession) session).getMessages();
        int systemCount = 0, userCount = 0, assistantCount = 0;
        for (Message msg : messages) {
            if ("system".equals(msg.role))
                systemCount++;
            else if ("user".equals(msg.role))
                userCount++;
            else if ("assistant".equals(msg.role))
                assistantCount++;
        }
        Log.d(TAG, "Current conversation has " + systemCount + " system, " +
                userCount + " user, and " + assistantCount + " assistant messages");

        // Format the iteration prompt (now includes currentCode)
        String prompt = formatIterationPrompt(description, currentCode, logcat, screenshot, feedback);

        // Queue the user message (with image attachment if provided)
        session.queueUserMessageWithImage(prompt, image);

        Log.d(TAG, "After queueing new user message, session now has " +
                ((ClaudeChatSession) session).getMessages().size() + " messages");

        // Print the last few messages of the session for debugging
        messages = ((ClaudeChatSession) session).getMessages();
        int startIdx = Math.max(0, messages.size() - 4); // Show last 4 messages or all if fewer
        StringBuilder recentMessages = new StringBuilder("Recent messages in session:\n");
        for (int i = startIdx; i < messages.size(); i++) {
            Message msg = messages.get(i);
            recentMessages.append(i).append(": ")
                    .append(msg.role)
                    .append(" - ")
                    .append(msg.content.length() > 50 ? msg.content.substring(0, 50) + "..." : msg.content)
                    .append("\n");
        }
        Log.d(TAG, recentMessages.toString());

        // Send all messages and get the response
        return session.sendMessages();
    }

    @Override
    public boolean clearApiKey() {
        try {
            ApiKeyManager.getInstance(context).clearApiKey(LLMClientFactory.LLMType.CLAUDE);
            currentModel = null;
            // Clear the model cache since different API keys might have different model
            // access
            clearModelCache();
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Error clearing Claude API key", e);
            return false;
        }
    }

    /**
     * Helper method to call the Claude API with message history.
     * Claude API requires converting multiple messages into a special format.
     */
    private String callClaudeAPI(List<Message> history) {
        try {
            Log.d(TAG, "DEBUG: callClaudeAPI started in thread: " + Thread.currentThread().getName());
            Log.d(TAG, "=== Calling Claude API ===");
            Log.d(TAG, "DEBUG: Message history size: " + history.size());

            // Detailed message history logging
            StringBuilder historyLog = new StringBuilder();
            historyLog.append("Message history summary:\n");
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                historyLog.append(i).append(": ")
                        .append(msg.role)
                        .append(" - ")
                        .append(msg.content.length() > 30 ? msg.content.substring(0, 30) + "..." : msg.content)
                        .append("\n");
            }
            Log.d(TAG, historyLog.toString());

            String apiKey = ApiKeyManager.getInstance(context).getApiKey(LLMClientFactory.LLMType.CLAUDE);
            Log.d(TAG, "DEBUG: ApiKey retrieved: " + (apiKey != null ? "Yes (not showing key)" : "No key found"));
            assert apiKey != null;

            ensureModelIsSet();
            Log.d(TAG, "DEBUG: Using Claude model: " + currentModel);

            Log.d(TAG, "DEBUG: Setting up HTTP connection to Claude API");
            URL url = new URL(API_BASE_URL + "/messages");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            conn.setDoOutput(true);
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            // Create Claude API compatible request
            JSONObject requestBody = new JSONObject();
            requestBody.put("model", currentModel);
            requestBody.put("max_tokens", 64000);
            requestBody.put("temperature", 0.7);

            // Convert message history to Claude message format
            JSONArray messagesArray = new JSONArray();

            // Add system message if present
            String systemPrompt = null;
            List<Message> nonSystemMessages = new ArrayList<>();

            // Extract system message and collect non-system messages
            for (Message msg : history) {
                if ("system".equals(msg.role)) {
                    // Use the first system message if one hasn't been set yet
                    if (systemPrompt == null) {
                        systemPrompt = msg.content;
                        Log.d(TAG, "Found system message: " +
                                (systemPrompt.length() > 50 ? systemPrompt.substring(0, 50) + "..." : systemPrompt));
                    } else {
                        Log.w(TAG, "Multiple system messages found, using first one only. " +
                                "Claude API only supports one system message.");
                    }
                } else {
                    nonSystemMessages.add(msg);
                }
            }

            // Add system message to request if found
            if (systemPrompt != null) {
                requestBody.put("system", systemPrompt);
            }

            // Add user/assistant messages from our filtered list
            for (Message msg : nonSystemMessages) {
                JSONObject messageObj = new JSONObject();
                messageObj.put("role", msg.role);
                messageObj.put("content", msg.content);
                messagesArray.put(messageObj);
            }

            requestBody.put("messages", messagesArray);

            String requestStr = requestBody.toString();
            Log.d(TAG, "DEBUG: Request body prepared, length: " + requestStr.length());
            Log.d(TAG, "╔══════════════════════════╗");
            Log.d(TAG, "║ START CLAUDE API REQUEST ║");
            Log.d(TAG, "╚══════════════════════════╝");
            // Use safe logging for potentially large JSON
            logJsonSafely(TAG, "Claude API request", requestStr, 1000);
            Log.d(TAG, "╔═════════════════════════╗");
            Log.d(TAG, "║ STOP CLAUDE API REQUEST ║");
            Log.d(TAG, "╚═════════════════════════╝");

            // Write the request
            Log.d(TAG, "DEBUG: Writing request to connection output stream");
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestStr.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            Log.d(TAG, "DEBUG: Getting response code");
            int responseCode = conn.getResponseCode();
            Log.d(TAG, "DEBUG: Claude API response code: " + responseCode);

            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Read the response
                Log.d(TAG, "DEBUG: Reading successful response");
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }

                    String jsonResponse = response.toString();
                    Log.d(TAG, "DEBUG: Raw JSON response received, length: " + jsonResponse.length());
                    Log.d(TAG, "╔═══════════════════════════╗");
                    Log.d(TAG, "║ START CLAUDE API RESPONSE ║");
                    Log.d(TAG, "╚═══════════════════════════╝");
                    // Use safe logging for potentially large JSON
                    logJsonSafely(TAG, "Claude API response", jsonResponse, 1000);
                    Log.d(TAG, "╔══════════════════════════╗");
                    Log.d(TAG, "║ STOP CLAUDE API RESPONSE ║");
                    Log.d(TAG, "╚══════════════════════════╝");

                    // Log response format detection for debugging
                    ResponseFormat format = detectResponseFormat(jsonResponse);
                    Log.d(TAG, "DEBUG: Detected response format: " + format);

                    // Extract the content from the response
                    Log.d(TAG, "DEBUG: Extracting content from response");
                    String extractedContent = extractTextFromResponse(jsonResponse);

                    // Log the full extracted content for debugging
                    Log.d(TAG, "╔═════════════════════════════╗");
                    Log.d(TAG, "║ START EXTRACTED RAW CONTENT ║");
                    Log.d(TAG, "╚═════════════════════════════╝");
                    logTextSafely(TAG, "Extracted content", extractedContent, 1000);
                    Log.d(TAG, "╔════════════════════════════╗");
                    Log.d(TAG, "║ STOP EXTRACTED RAW CONTENT ║");
                    Log.d(TAG, "╚════════════════════════════╝");

                    return extractedContent;
                }
            } else {
                // Handle error response
                Log.d(TAG, "DEBUG: Reading error response, code: " + responseCode);
                String errorResponse = "";
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    errorResponse = response.toString();
                }

                Log.e(TAG, "ERROR: Claude API error response (" + responseCode + "): " + errorResponse);
                throw new RuntimeException("Claude API error: " + responseCode + " - " + errorResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Exception calling Claude API", e);
            throw new RuntimeException("Failed to call Claude API: " + e.getMessage(), e);
        }
    }

    /**
     * Safely log JSON content with a specified maximum length
     * to avoid overwhelming logcat
     */
    private void logJsonSafely(String tag, String label, String json, int maxLength) {
        if (json == null) {
            Log.d(tag, label + ": null");
            return;
        }

        if (json.length() <= maxLength) {
            Log.d(tag, label + ": " + json);
        } else {
            Log.d(tag, label + " (truncated to " + maxLength + " chars): " +
                    json.substring(0, maxLength) + "...");
        }
    }

    /**
     * Safely log text content with a specified maximum length
     */
    private void logTextSafely(String tag, String label, String text, int maxLength) {
        if (text == null) {
            Log.d(tag, label + ": null");
            return;
        }

        if (text.length() <= maxLength) {
            Log.d(tag, label + ": " + text);
        } else {
            Log.d(tag, label + " (truncated to " + maxLength + " chars): " +
                    text.substring(0, maxLength) + "...");
        }
    }

    /**
     * Extracts the text content from the Claude API response.
     *
     * @param jsonResponse The JSON response from the Claude API
     * @return The extracted text
     */
    private String extractTextFromResponse(String jsonResponse) {
        try {
            Log.d(TAG, "Starting extractTextFromResponse with response length: " + jsonResponse.length());

            // Determine the response format
            ResponseFormat format = detectResponseFormat(jsonResponse);
            Log.d(TAG, "Detected response format: " + format);

            // Process according to format
            switch (format) {
                case ARRAY:
                    return extractTextFromArrayResponse(jsonResponse);
                case OBJECT:
                    return extractTextFromObjectResponse(jsonResponse);
                case MALFORMED:
                default:
                    // Try direct markdown extraction as a last resort
                    String extractedCode = extractCodeFromMarkdown(jsonResponse);
                    if (extractedCode != null) {
                        Log.d(TAG, "Extracted code from malformed response using markdown parser");
                        return extractedCode;
                    }

                    Log.w(TAG, "Could not parse response in any known format");
                    throw new RuntimeException("Could not parse response in any known format");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error extracting text from Claude response", e);
            throw new RuntimeException("Error extracting text from Claude response", e);
        }
    }

    /**
     * Enum representing different Claude API response formats
     */
    private enum ResponseFormat {
        ARRAY, // Claude 3.7 array format: [{type:"text", text:"content"}, ...]
        OBJECT, // Traditional object format
        MALFORMED // Unrecognized or invalid format
    }

    /**
     * Detect the format of the Claude API response
     */
    private ResponseFormat detectResponseFormat(String jsonResponse) {
        if (jsonResponse == null || jsonResponse.trim().isEmpty()) {
            Log.w(TAG, "Empty response from Claude API");
            return ResponseFormat.MALFORMED;
        }

        // Trim whitespace for more reliable prefix checking
        String trimmed = jsonResponse.trim();

        // Check for array format (Claude 3.7)
        if (trimmed.startsWith("[")) {
            // Additional validation for array format
            if (trimmed.contains("\"type\":\"text\"") ||
                    trimmed.contains("\"type\": \"text\"")) {
                return ResponseFormat.ARRAY;
            }

            // It's an array but not the expected format
            Log.w(TAG, "Response is JSON array but doesn't match expected Claude 3.7 format");
        }

        // Check for object format
        if (trimmed.startsWith("{")) {
            try {
                // Quick check if it's valid JSON object
                new JSONObject(jsonResponse);
                return ResponseFormat.OBJECT;
            } catch (Exception e) {
                Log.w(TAG, "Response starts with '{' but isn't valid JSON object", e);
            }
        }

        // If we get here, the format is not recognized
        Log.w(TAG, "Unrecognized response format from Claude API: " +
                trimmed.substring(0, Math.min(trimmed.length(), 100)) + "...");
        return ResponseFormat.MALFORMED;
    }

    /**
     * Extract text content from a Claude API response in array format (Claude 3.7)
     */
    private String extractTextFromArrayResponse(String jsonResponse) {
        try {
            Log.d(TAG, "Processing Claude 3.7 array response format");
            JSONArray array = new JSONArray(jsonResponse);
            StringBuilder fullContent = new StringBuilder();

            // Extract all text content from the array items
            for (int i = 0; i < array.length(); i++) {
                try {
                    JSONObject item = array.getJSONObject(i);
                    if (item.has("type") && "text".equals(item.getString("type")) && item.has("text")) {
                        String text = item.getString("text");
                        text = replaceEscapedCharacters(text);
                        fullContent.append(text);
                    } else {
                        Log.d(TAG, "Skipping array item without 'text' field or not of type 'text': " +
                                item.toString().substring(0, Math.min(item.toString().length(), 100)));
                    }
                } catch (Exception e) {
                    // Log but continue processing other items
                    Log.e(TAG, "Error processing array item at index " + i, e);
                }
            }

            String contentText = fullContent.toString();
            if (contentText.isEmpty()) {
                Log.w(TAG, "No text content found in array response: " +
                        jsonResponse.substring(0, Math.min(jsonResponse.length(), 200)));
                throw new RuntimeException("No text content found in array response");
            }

            // Extract code blocks from text content
            String extractedCode = extractCodeFromMarkdown(contentText);
            if (extractedCode != null) {
                return extractedCode;
            }

            // If we have content but no code blocks were found, log this unusual case
            Log.d(TAG, "No code blocks found in array response content, returning raw text");
            return contentText;
        } catch (Exception e) {
            Log.e(TAG, "Error processing array response: " + e.getMessage(), e);
            // Log a sample of the problematic JSON for debugging
            try {
                String sample = jsonResponse.substring(0, Math.min(jsonResponse.length(), 500));
                Log.e(TAG, "Sample of problematic JSON: " + sample);
            } catch (Exception ex) {
                Log.e(TAG, "Could not get sample of JSON", ex);
            }
            throw new RuntimeException("Error processing array response", e);
        }
    }

    /**
     * Extract text content from a Claude API response in object format (older
     * Claude API)
     */
    private String extractTextFromObjectResponse(String jsonResponse) {
        try {
            Log.d(TAG, "Processing response as JSON object");
            JSONObject json = new JSONObject(jsonResponse);

            // Format 1: Check if the response contains the content object
            if (json.has("content") && !json.isNull("content")) {
                Object contentObj = json.get("content");

                // Handle content as JSONObject
                if (contentObj instanceof JSONObject) {
                    Log.d(TAG, "Found content as JSONObject");
                    JSONObject content = json.getJSONObject("content");

                    // Check if the content has parts array
                    if (content.has("parts") && !content.isNull("parts")) {
                        JSONArray parts = content.getJSONArray("parts");

                        if (parts.length() > 0) {
                            String text = parts.getString(0);
                            Log.d(TAG, "Successfully extracted text from content.parts[0]");
                            return text;
                        }
                    }
                }
                // Handle content as JSONArray
                else if (contentObj instanceof JSONArray) {
                    Log.d(TAG, "Found content as JSONArray in object response");
                    JSONArray contentArray = json.getJSONArray("content");

                    StringBuilder fullText = new StringBuilder();
                    for (int i = 0; i < contentArray.length(); i++) {
                        try {
                            JSONObject contentPart = contentArray.getJSONObject(i);
                            if (contentPart.has("type") && contentPart.getString("type").equals("text") &&
                                    contentPart.has("text")) {
                                fullText.append(contentPart.getString("text"));
                            }
                        } catch (Exception e) {
                            Log.e(TAG, "Error processing content array item at index " + i, e);
                        }
                    }

                    if (fullText.length() > 0) {
                        Log.d(TAG, "Successfully built text from content array items");
                        return fullText.toString();
                    }
                } else {
                    Log.w(TAG, "Content field is neither a JSONObject nor JSONArray: " +
                            contentObj.getClass().getName());
                }
            }

            // Format 2: Check if response has a message object with content
            if (json.has("message") && !json.isNull("message")) {
                Log.d(TAG, "Found message object in response");
                JSONObject message = json.getJSONObject("message");

                if (message.has("content") && !message.isNull("content")) {
                    JSONArray content = message.getJSONArray("content");

                    if (content.length() > 0) {
                        JSONObject part = content.getJSONObject(0);
                        if (part.has("text") && !part.isNull("text")) {
                            Log.d(TAG, "Successfully extracted text from message.content[0].text");
                            return part.getString("text");
                        }
                    }
                }
            }

            // Extract code from Markdown code blocks as a last resort
            String markdownExtracted = extractCodeFromMarkdown(jsonResponse);
            if (markdownExtracted != null) {
                Log.d(TAG, "Successfully extracted code from markdown in object response");
                return markdownExtracted;
            }

            Log.w(TAG, "Could not extract content from object response: " +
                    jsonResponse.substring(0, Math.min(jsonResponse.length(), 200)));
            throw new RuntimeException("Could not extract content from object response");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing response as JSON object: " + e.getMessage(), e);
            throw new RuntimeException("Error parsing response as JSON object", e);
        }
    }

    /**
     * Helper method to extract code from a Markdown-formatted response
     *
     * @param response The response to parse
     * @return The extracted code or null if none found
     */
    private String extractCodeFromMarkdown(String response) {
        try {
            if (response == null || response.isEmpty()) {
                return null;
            }

            Log.d(TAG, "Attempting to extract code from markdown content");

            // Look for Clojure code blocks in markdown format
            int start = response.indexOf("```clojure");
            if (start >= 0) {
                start = response.indexOf("\n", start) + 1;
                int end = response.indexOf("```", start);
                if (end > start) {
                    String code = response.substring(start, end).trim();
                    Log.d(TAG, "Extracted Clojure code block, length: " + code.length());
                    return code;
                }
            }

            // Try with just backticks (no language specifier)
            start = response.indexOf("```");
            if (start >= 0) {
                // Skip the line containing only ```
                start = response.indexOf("\n", start) + 1;
                // Find the closing ```
                int end = response.indexOf("```", start);
                if (end > start) {
                    String code = response.substring(start, end).trim();
                    Log.d(TAG, "Extracted generic code block, length: " + code.length());
                    return code;
                }
            }

            // Look for indented code blocks as a last resort
            if (response.contains("    ")) {
                // Find consecutive lines that start with 4 spaces
                String[] lines = response.split("\n");
                StringBuilder codeBlock = new StringBuilder();
                boolean inCodeBlock = false;
                int codeLines = 0;

                for (String line : lines) {
                    if (line.startsWith("    ") || line.startsWith("\t")) {
                        if (!inCodeBlock) {
                            inCodeBlock = true;
                        }
                        // Add the line without the indentation
                        codeBlock.append(line.substring(line.startsWith("    ") ? 4 : 1)).append("\n");
                        codeLines++;
                    } else if (inCodeBlock && line.trim().isEmpty()) {
                        // Empty lines within a code block are preserved
                        codeBlock.append("\n");
                    } else if (inCodeBlock) {
                        // End of code block
                        break;
                    }
                }

                // Only return if we found a significant code block (at least 3 lines)
                if (codeLines >= 3) {
                    String code = codeBlock.toString().trim();
                    Log.d(TAG, "Extracted indented code block, length: " + code.length());
                    return code;
                }
            }

            return null;
        } catch (Exception e) {
            Log.e(TAG, "Error extracting code from markdown", e);
            return null;
        }
    }

    /**
     * Fetches available Claude models from the API with caching
     */
    public static List<String> fetchAvailableModels(Context context) {
        // Check if we have a valid cached result
        if (cachedModels != null) {
            Log.d(TAG, "Returning cached Claude models");
            return new ArrayList<>(cachedModels);
        }

        Log.d(TAG, "Fetching Claude models from API (cache miss or expired)");
        List<String> models = new ArrayList<>();
        String apiKey = ApiKeyManager.getInstance(context).getApiKey(LLMClientFactory.LLMType.CLAUDE);

        if (apiKey == null || apiKey.isEmpty()) {
            Log.w(TAG, "No Claude API key available");
            return getDefaultModels(); // Use default models instead of throwing exception
        }

        HttpURLConnection conn = null;
        try {
            URL url = new URL(API_BASE_URL + "/models");
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", API_VERSION);
            conn.setConnectTimeout(HTTP_TIMEOUT);
            conn.setReadTimeout(HTTP_TIMEOUT);

            int responseCode = conn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8));
                String inputLine;
                StringBuilder response = new StringBuilder();

                while ((inputLine = in.readLine()) != null) {
                    response.append(inputLine);
                }
                in.close();

                String responseStr = response.toString();
                Log.d(TAG, "Claude models API response: " + responseStr);

                JSONObject jsonResponse = new JSONObject(responseStr);

                // Check if the response has the expected 'data' field
                if (jsonResponse.has("data") && !jsonResponse.isNull("data")) {
                    JSONArray data = jsonResponse.getJSONArray("data");

                    for (int i = 0; i < data.length(); i++) {
                        JSONObject model = data.getJSONObject(i);

                        // Handle different API response formats
                        if (model.has("id")) {
                            models.add(model.getString("id"));
                        } else if (model.has("name")) {
                            models.add(model.getString("name"));
                        } else {
                            Log.w(TAG, "Model object missing id or name: " + model.toString());
                        }
                    }
                } else {
                    Log.w(TAG, "Response doesn't contain 'data' array. Using default models.");
                }

                if (!models.isEmpty()) {
                    // Sort models with Claude 3 Opus first
                    models.sort((a, b) -> {
                        if (a.contains("opus") && !b.contains("opus"))
                            return -1;
                        if (!a.contains("opus") && b.contains("opus"))
                            return 1;
                        // Then prefer Claude 3 models over Claude 2 models
                        if (a.contains("claude-3") && !b.contains("claude-3"))
                            return -1;
                        if (!a.contains("claude-3") && b.contains("claude-3"))
                            return 1;
                        return a.compareTo(b);
                    });

                    Log.d(TAG, "Successfully fetched Claude models: " + models);
                }
            } else {
                String errorResponse = "";
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                    StringBuilder response = new StringBuilder();
                    String responseLine;
                    while ((responseLine = br.readLine()) != null) {
                        response.append(responseLine.trim());
                    }
                    errorResponse = response.toString();
                }
                Log.e(TAG, "Error getting models. Response code: " + responseCode + ", Error: " + errorResponse);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching Claude models", e);
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }

        // If models list is empty after fetching, provide default models
        if (models.isEmpty()) {
            return getDefaultModels();
        }

        // Cache the successful result
        cachedModels = new ArrayList<>(models);
        Log.d(TAG, "Cached Claude models for future use");

        return models;
    }

    /**
     * Clears the cached models (useful for testing or when API key changes)
     */
    public static void clearModelCache() {
        cachedModels = null;
        Log.d(TAG, "Cleared Claude model cache");
    }

    /**
     * Get model properties for a specific model
     * 
     * @param modelName The name of the model
     * @return ModelProperties for the model, or null if not found
     */
    public static ModelProperties getModelProperties(String modelName) {
        // TODO: Implement Claude model properties lookup table
        return null;
    }

    private static List<String> getDefaultModels() {
        Log.d(TAG, "Using default Claude models");
        List<String> models = new ArrayList<>();
        models.add("claude-3-5-sonnet-20240620"); // Add newer models first
        models.add("claude-3-sonnet-20240229");
        models.add("claude-3-opus-20240229");
        models.add("claude-3-haiku-20240307");
        return models;
    }

    /**
     * Replace escaped characters like \n, \t, etc. with their actual character
     * representations
     */
    private String replaceEscapedCharacters(String input) {
        if (input == null)
            return null;

        try {
            // First try more robust approach
            StringBuilder output = new StringBuilder();
            boolean escaped = false;
            for (int i = 0; i < input.length(); i++) {
                char c = input.charAt(i);
                if (escaped) {
                    switch (c) {
                        case 'n':
                            output.append('\n');
                            break;
                        case 't':
                            output.append('\t');
                            break;
                        case 'r':
                            output.append('\r');
                            break;
                        case '\\':
                            output.append('\\');
                            break;
                        case '"':
                            output.append('"');
                            break;
                        default:
                            output.append(c);
                            break;
                    }
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else {
                    output.append(c);
                }
            }

            // Handle any trailing backslash
            if (escaped) {
                output.append('\\');
            }

            String result = output.toString();

            // Also try to fix any weird spacing issues from the API
            // Sometimes spaces appear in the middle of words
            result = result.replaceAll("\\s{2,}", " ");

            // Log if changes were made
            if (!result.equals(input)) {
                Log.d(TAG, "Replaced escaped characters and fixed spacing in text");
            }

            return result;
        } catch (Exception e) {
            Log.e(TAG, "Error replacing escaped characters", e);

            // Fallback to simple replacement
            String result = input
                    .replace("\\n", "\n")
                    .replace("\\t", "\t")
                    .replace("\\r", "\r")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\");

            return result;
        }
    }
}
