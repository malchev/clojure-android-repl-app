package com.example.clojurerepl;

import android.content.Context;
import android.util.Log;
import com.example.clojurerepl.auth.ApiKeyManager;
import java.util.List;
import java.util.Arrays;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.json.JSONObject;
import org.json.JSONArray;
import java.util.ArrayList;

public class LLMClientFactory {
    private static final String TAG = "LLMClientFactory";

    public enum LLMType {
        GEMINI,
        OPENAI,
        STUB
    }

    public static LLMClient createClient(Context context, LLMType type, String modelName) {
        Log.d(TAG, "Creating LLM client of type: " + type);
        switch (type) {
            case GEMINI:
                GeminiLLMClient geminiClient = new GeminiLLMClient(context);
                if (modelName != null) {
                    geminiClient.setModel(modelName);
                }
                return geminiClient;
            case OPENAI:
                OpenAIChatClient openaiClient = new OpenAIChatClient(context);
                if (modelName != null) {
                    openaiClient.setModel(modelName);
                }
                return openaiClient;
            case STUB:
                return new StubLLMClient(context);
            default:
                throw new IllegalArgumentException("Unknown LLM type: " + type);
        }
    }

    public static LLMClient createClient(Context context, LLMType type) {
        return createClient(context, type, null);
    }

    public static List<String> getAvailableModels(Context context, LLMType type) {
        Log.d(TAG, "Getting available models for type: " + type);
        switch (type) {
            case GEMINI:
                return Arrays.asList("gemini-pro", "gemini-pro-vision");
            case OPENAI:
                return fetchOpenAIModels(context);
            case STUB:
                return Arrays.asList("stub-model");
            default:
                throw new IllegalArgumentException("Unknown LLM type: " + type);
        }
    }

    private static List<String> fetchOpenAIModels(Context context) {
        Log.d(TAG, "Fetching OpenAI models");
        List<String> models = new ArrayList<>();
        ApiKeyManager apiKeyManager = ApiKeyManager.getInstance(context);
        String apiKey = apiKeyManager.getApiKey(LLMType.OPENAI);

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

                Log.d(TAG, "Successfully fetched OpenAI models: " + models);
            } else {
                Log.e(TAG, "Failed to fetch OpenAI models. Response code: " + responseCode);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error fetching OpenAI models", e);
        }

        return models;
    }
}
