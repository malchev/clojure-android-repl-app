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
import java.io.File;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

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

    // Track the current request for cancellation
    private final AtomicReference<CancellableCompletableFuture<String>> currentRequest = new AtomicReference<>();

    // Static cache for available models
    private static List<String> cachedModels = null;

    public ClaudeLLMClient(Context context, ChatSession chatSession) {
        super(context, chatSession);
        Log.d(TAG, "Creating new ClaudeLLMClient with " + chatSession.getMessages().size() + " messages.");
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

    @Override
    protected CancellableCompletableFuture<String> sendMessages(ChatSession session) {
        Log.d(TAG,
                "DEBUG: ClaudeLLMClient.sendMessages called with " + session.getMessages().size()
                        + " messages in session: "
                        + session.getSessionId());

        // Cancel any existing request
        cancelCurrentRequest();

        // Create a new cancellable future
        CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
        currentRequest.set(future);

        // Log message types and short previews for debugging
        for (Message msg : session.getMessages()) {
            String preview = msg.content.length() > 50 ? msg.content.substring(0, 50) + "..." : msg.content;
            // Use the same role mapping as the API call for consistent logging
            String claudeRole;
            if (MessageRole.SYSTEM.equals(msg.role)) {
                claudeRole = "system";
            } else if (MessageRole.USER.equals(msg.role)) {
                claudeRole = "user";
            } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                claudeRole = "assistant";
            } else {
                claudeRole = msg.role.getApiValue();
            }
            Log.d(TAG, "DEBUG: Message type: " + claudeRole + ", content: " + preview);
        }

        CompletableFuture.runAsync(() -> {
            Thread.currentThread().setName("Claude-API-Thread");
            Log.d(TAG, "DEBUG: Inside CompletableFuture thread: " + Thread.currentThread().getName());
            try {
                // Check if cancelled before starting
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled before starting");
                    return;
                }

                Log.d(TAG, "DEBUG: About to call Claude API in thread: " + Thread.currentThread().getName());
                String response = null;
                try {
                    response = callClaudeAPI(session.getMessages(), future);
                    Log.d(TAG, "DEBUG: Claude API call completed successfully");
                } catch (Exception e) {
                    Log.e(TAG, "ERROR: callClaudeAPI failed", e);
                    if (!future.isCancelled()) {
                        future.completeExceptionally(e);
                    }
                    return;
                }

                // Check if cancelled after API call
                if (future.isCancelled()) {
                    Log.d(TAG, "Request was cancelled after API call");
                    return;
                }

                Log.d(TAG, "=== FULL CLAUDE RESPONSE ===\n" +
                        (response != null
                                ? (response.length() > 1000 ? response.substring(0, 1000) + "..." : response)
                                : "NULL RESPONSE"));

                // Add assistant message to history
                session.queueAssistantResponse(response, getType(), getModel());

                future.complete(response);
            } catch (Exception e) {
                Log.e(TAG, "ERROR: Exception in sendMessages CompletableFuture", e);
                // Check if this is a cancellation exception, which is expected behavior
                if (e instanceof CancellationException ||
                        (e instanceof RuntimeException && e.getCause() instanceof CancellationException)) {
                    Log.d(TAG, "Claude chat session was cancelled - this is expected behavior");
                    if (!future.isCancelled()) {
                        future.completeExceptionally(e);
                    }
                    return;
                }

                if (!future.isCancelled()) {
                    future.completeExceptionally(
                            new RuntimeException("ERROR: Exception in sendMessages CompletableFuture", e));
                }
            } finally {
                // Clear the current request reference
                currentRequest.compareAndSet(future, null);
            }
        });

        return future;
    }

    @Override
    public CancellableCompletableFuture<String> generateInitialCode(UUID sessionId, String description) {
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

            // Queue system prompt and format initial prompt
            chatSession.queueSystemPrompt(getSystemPrompt());
            String prompt = formatInitialPrompt(description, null);
            chatSession.queueUserMessage(prompt, null, null, null);
            Log.d(TAG, "DEBUG: Created chat session, about to send messages to Claude API");

            CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
            sendMessages(chatSession).handle((response, ex) -> {
                if (ex != null) {
                    // Remove the messages we added before sendMessages (system prompt + user
                    // message = 2 messages)
                    chatSession.removeLastMessages(2);
                    Log.d(TAG, "Removed 2 messages (system prompt + user message) due to failure");

                    // Check if this is a cancellation exception, which is expected behavior
                    if (ex instanceof CancellationException ||
                            (ex instanceof RuntimeException && ex.getCause() instanceof CancellationException)) {
                        Log.d(TAG, "Claude initial code generation was cancelled - this is expected behavior");
                        future.completeExceptionally(ex);
                    } else {
                        Log.e(TAG, "ERROR: Failed in Claude sendMessages", ex);
                        future.complete("ERROR: " + ex.getMessage());
                    }
                } else {
                    Log.d(TAG, "SUCCESS: Claude sendMessages completed successfully");
                    future.complete(response);
                }
                return null;
            });
            return future;
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Exception in generateInitialCode", e);
            CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CancellableCompletableFuture<String> generateInitialCode(UUID sessionId, String description,
            String initialCode) {
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

            // Queue system prompt and format initial prompt
            chatSession.queueSystemPrompt(getSystemPrompt());
            String prompt = formatInitialPrompt(description, initialCode);
            chatSession.queueUserMessage(prompt, null, null, initialCode);
            Log.d(TAG, "DEBUG: Created chat session with template, about to send messages to Claude API");

            CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
            sendMessages(chatSession).handle((response, ex) -> {
                if (ex != null) {
                    // Remove the messages we added before sendMessages (system prompt + user
                    // message = 2 messages)
                    chatSession.removeLastMessages(2);
                    Log.d(TAG, "Removed 2 messages (system prompt + user message) due to failure");

                    // Check if this is a cancellation exception, which is expected behavior
                    if (ex instanceof CancellationException ||
                            (ex instanceof RuntimeException && ex.getCause() instanceof CancellationException)) {
                        Log.d(TAG,
                                "Claude initial code generation with template was cancelled - this is expected behavior");
                        future.completeExceptionally(ex);
                    } else {
                        Log.e(TAG, "ERROR: Failed in Claude sendMessages with template", ex);
                        future.complete("ERROR: " + ex.getMessage());
                    }
                } else {
                    Log.d(TAG, "SUCCESS: Claude sendMessages with template completed successfully");
                    future.complete(response);
                }
                return null;
            });
            return future;
        } catch (Exception e) {
            Log.e(TAG, "ERROR: Exception in generateInitialCode with template", e);
            CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
            future.completeExceptionally(e);
            return future;
        }
    }

    @Override
    public CancellableCompletableFuture<String> generateNextIteration(
            UUID sessionId,
            String description,
            String currentCode,
            String logcat,
            String feedback,
            List<File> images) {
        Log.d(TAG, "\n" +
                "┌───────────────────────────────────────────┐\n" +
                "│         GENERATING NEXT ITERATION         │\n" +
                "└───────────────────────────────────────────┘");

        // Check if images are provided and model is multimodal
        if (images != null && !images.isEmpty()) {
            ModelProperties props = getModelProperties(getModel());
            if (props == null || !props.isMultimodal) {
                CancellableCompletableFuture<String> future = new CancellableCompletableFuture<>();
                future.completeExceptionally(
                        new UnsupportedOperationException("Images parameter provided but model is not multimodal"));
                return future;
            }
        }

        Log.d(TAG, "Starting generateNextIteration with session containing " +
                chatSession.getMessages().size() + " messages");

        // Add logging of the current message types
        List<Message> messages = chatSession.getMessages();
        int systemCount = 0, userCount = 0, assistantCount = 0;
        for (Message msg : messages) {
            if (MessageRole.SYSTEM.equals(msg.role))
                systemCount++;
            else if (MessageRole.USER.equals(msg.role))
                userCount++;
            else if (MessageRole.ASSISTANT.equals(msg.role))
                assistantCount++;
        }
        Log.d(TAG, "Current conversation has " + systemCount + " system, " +
                userCount + " user, and " + assistantCount + " assistant messages");

        // Format the iteration prompt (now includes currentCode)
        String prompt = formatIterationPrompt(description, currentCode, logcat, feedback,
                images != null && !images.isEmpty());

        // Queue the user message (with images attachment if provided)
        chatSession.queueUserMessageWithImages(prompt, images, logcat, feedback, null);

        Log.d(TAG, "After queueing new user message, session now has " +
                chatSession.getMessages().size() + " messages");

        // Print the last few messages of the session for debugging
        messages = chatSession.getMessages();
        int startIdx = Math.max(0, messages.size() - 4); // Show last 4 messages or all if fewer
        StringBuilder recentMessages = new StringBuilder("Recent messages in session:\n");
        for (int i = startIdx; i < messages.size(); i++) {
            Message msg = messages.get(i);
            // Use the same role mapping as the API call for consistent logging
            String claudeRole;
            if (MessageRole.SYSTEM.equals(msg.role)) {
                claudeRole = "system";
            } else if (MessageRole.USER.equals(msg.role)) {
                claudeRole = "user";
            } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                claudeRole = "assistant";
            } else {
                claudeRole = msg.role.getApiValue();
            }
            recentMessages.append(i).append(": ")
                    .append(claudeRole)
                    .append(" - ")
                    .append(msg.content.length() > 50 ? msg.content.substring(0, 50) + "..." : msg.content)
                    .append("\n");
        }
        Log.d(TAG, recentMessages.toString());

        // Send all messages and get the response
        CancellableCompletableFuture<String> future = sendMessages(chatSession);
        CancellableCompletableFuture<String> wrappedFuture = new CancellableCompletableFuture<>();

        future.handle((response, ex) -> {
            if (ex != null) {
                // Remove the user message we added before sendMessages (1 message)
                chatSession.removeLastMessages(1);
                Log.d(TAG, "Removed 1 message (user message) due to failure in generateNextIteration");
                wrappedFuture.completeExceptionally(ex);
            } else {
                wrappedFuture.complete(response);
            }
            return null;
        });

        return wrappedFuture;
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

    @Override
    public boolean cancelCurrentRequest() {
        CancellableCompletableFuture<String> request = currentRequest.get();
        if (request != null && !request.isCancelledOrCompleted()) {
            Log.d(TAG, "Cancelling current Claude API request");
            boolean cancelled = request.cancel(true);
            currentRequest.set(null);
            return cancelled;
        }
        return false;
    }

    /**
     * Helper method to call the Claude API with message history.
     * Claude API requires converting multiple messages into a special format.
     */
    private String callClaudeAPI(List<Message> history, CancellableCompletableFuture<String> future) {
        try {
            Log.d(TAG, "DEBUG: callClaudeAPI started in thread: " + Thread.currentThread().getName());
            Log.d(TAG, "=== Calling Claude API ===");
            Log.d(TAG, "DEBUG: Message history size: " + history.size());

            // Detailed message history logging
            StringBuilder historyLog = new StringBuilder();
            historyLog.append("Message history summary:\n");
            for (int i = 0; i < history.size(); i++) {
                Message msg = history.get(i);
                // Use the same role mapping as the API call for consistent logging
                String claudeRole;
                if (MessageRole.SYSTEM.equals(msg.role)) {
                    claudeRole = "system";
                } else if (MessageRole.USER.equals(msg.role)) {
                    claudeRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    claudeRole = "assistant";
                } else {
                    claudeRole = msg.role.getApiValue();
                }
                historyLog.append(i).append(": ")
                        .append(claudeRole)
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

            // Use model-specific output token limit instead of hard-coded value
            ModelProperties modelProps = getModelProperties(currentModel);
            int maxTokens = modelProps != null ? modelProps.maxOutputTokens : 4096; // fallback to 4K
            requestBody.put("max_tokens", maxTokens);
            Log.d(TAG, "Using max_tokens: " + maxTokens + " for model: " + currentModel);

            requestBody.put("temperature", 0.7);

            // Convert message history to Claude message format
            JSONArray messagesArray = new JSONArray();

            // Add system message if present
            String systemPrompt = null;
            List<Message> nonSystemMessages = new ArrayList<>();

            // Extract system message and collect non-system messages
            for (Message msg : history) {
                if (MessageRole.SYSTEM.equals(msg.role)) {
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
                // Claude API expects "user" and "assistant" roles
                String claudeRole;
                if (MessageRole.USER.equals(msg.role)) {
                    claudeRole = "user";
                } else if (MessageRole.ASSISTANT.equals(msg.role)) {
                    claudeRole = "assistant";
                } else {
                    // Fallback for any other roles
                    claudeRole = msg.role.getApiValue();
                    Log.w(TAG, "Unknown role for Claude API: " + msg.role + ", using: " + claudeRole);
                }
                messageObj.put("role", claudeRole);

                // For Claude API, we need to handle both text and image content
                if (msg.role == LLMClient.MessageRole.USER && ((LLMClient.UserMessage) msg).hasImages()) {
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
                            imageContent.put("type", "image");
                            JSONObject imageSource = new JSONObject();
                            imageSource.put("type", "base64");
                            imageSource.put("media_type", mimeType);
                            imageSource.put("data", base64Image);
                            imageContent.put("source", imageSource);
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

                    messageObj.put("content", contentArray);
                } else {
                    // Regular text-only message
                    messageObj.put("content", msg.content);
                }

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

            // Check for cancellation before writing request
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before writing to connection");
                throw new CancellationException("Request was cancelled");
            }

            // Write the request
            Log.d(TAG, "DEBUG: Writing request to connection output stream");
            try (OutputStream os = conn.getOutputStream()) {
                byte[] input = requestStr.getBytes(StandardCharsets.UTF_8);
                os.write(input, 0, input.length);
            }

            // Check for cancellation before getting response
            if (future.isCancelled()) {
                Log.d(TAG, "Request cancelled before getting response");
                throw new CancellationException("Request was cancelled");
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

            Log.d(TAG, "Returning raw text");
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

            Log.w(TAG, "Could not extract content from object response: " +
                    jsonResponse.substring(0, Math.min(jsonResponse.length(), 200)));
            throw new RuntimeException("Could not extract content from object response");
        } catch (Exception e) {
            Log.e(TAG, "Error parsing response as JSON object: " + e.getMessage(), e);
            throw new RuntimeException("Error parsing response as JSON object", e);
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
        if (modelName == null) {
            return null;
        }

        // Claude 4 Opus (latest - most powerful)
        if (modelName.contains("claude-4-opus")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    32000, // 32K output limit
                    true, // multimodal
                    "Current");
        }

        // Claude 4 Sonnet
        if (modelName.contains("claude-sonnet-4") || modelName.contains("claude-4-sonnet")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    64000, // 64K output limit
                    true, // multimodal
                    "Current");
        }

        // Claude 3.7 Sonnet (with extended output capability)
        if (modelName.contains("claude-3-7-sonnet")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    8192, // 8K standard output limit (128K extended available)
                    true, // multimodal
                    "Current");
        }

        // Claude 3.5 Sonnet variants (8K output limit since July 2024)
        if (modelName.contains("claude-3-5-sonnet")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    8192, // 8K output limit
                    true, // multimodal
                    "Current");
        }

        // Claude 3.5 Haiku (8K output limit)
        if (modelName.contains("claude-3-5-haiku")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    8192, // 8K output limit
                    true, // multimodal
                    "Current");
        }

        // Claude 3 Opus (legacy - 4K output limit)
        if (modelName.contains("claude-3-opus")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    4096, // 4K output limit
                    true, // multimodal
                    "Legacy");
        }

        // Claude 3 Sonnet (legacy - 4K output limit)
        if (modelName.contains("claude-3-sonnet")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    4096, // 4K output limit
                    true, // multimodal
                    "Legacy");
        }

        // Claude 3 Haiku (legacy - 4K output limit)
        if (modelName.contains("claude-3-haiku")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    4096, // 4K output limit
                    true, // multimodal
                    "Legacy");
        }

        // Claude 2.1 (November 2023)
        if (modelName.contains("claude-2-1")) {
            return new ModelProperties(
                    200000, // 200K context window (input)
                    4096, // 4K output limit (estimated)
                    false, // not multimodal
                    "Legacy");
        }

        // Claude 2.0 (July 2023)
        if (modelName.contains("claude-2")) {
            return new ModelProperties(
                    100000, // 100K context window (input)
                    4096, // 4K output limit (estimated)
                    false, // not multimodal
                    "Legacy");
        }

        // Claude Instant (various versions)
        if (modelName.contains("claude-instant")) {
            return new ModelProperties(
                    100000, // 100K context window (input)
                    4096, // 4K output limit (estimated)
                    false, // not multimodal
                    "Legacy");
        }

        // Default fallback for unknown models
        Log.w(TAG, "Unknown Claude model: " + modelName + ", using default properties");
        return new ModelProperties(
                100000, // Default to 100K context window (input)
                4096, // Default to 4K output limit
                false, // Default to not multimodal
                "Unknown");
    }

    private static List<String> getDefaultModels() {
        Log.d(TAG, "Using default Claude models");
        List<String> models = new ArrayList<>();
        models.add("claude-4-opus"); // Latest Claude 4 Opus (most powerful)
        models.add("claude-sonnet-4-20250514"); // Claude 4 Sonnet (efficient)
        models.add("claude-3-7-sonnet-20250219"); // Claude 3.7 Sonnet (extended output)
        models.add("claude-3-5-sonnet-20241022"); // Claude 3.5 Sonnet (October 2024)
        models.add("claude-3-5-haiku-20241022"); // Claude 3.5 Haiku (October 2024)
        models.add("claude-3-5-sonnet-20240620"); // Claude 3.5 Sonnet (June 2024)
        models.add("claude-3-opus-20240229"); // Claude 3 Opus (legacy)
        models.add("claude-3-sonnet-20240229"); // Claude 3 Sonnet (legacy)
        models.add("claude-3-haiku-20240307"); // Claude 3 Haiku (legacy)
        return models;
    }

    /**
     * Determines the MIME type of an image file based on its extension
     *
     * @param imageFile The image file
     * @return The MIME type string
     */

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
