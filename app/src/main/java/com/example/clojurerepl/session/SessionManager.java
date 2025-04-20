package com.example.clojurerepl.session;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Manages design sessions by providing methods to save, load, and delete
 * sessions.
 */
public class SessionManager {
    private static final String TAG = "SessionManager";
    private static final String SESSIONS_DIR = "sessions";
    private static final String SESSIONS_FILE = "design_sessions.json";

    private static SessionManager instance;
    private Context context;
    private List<DesignSession> sessions;

    private SessionManager(Context context) {
        this.context = context.getApplicationContext();
        this.sessions = new ArrayList<>();
        loadSessions();
    }

    public static synchronized SessionManager getInstance(Context context) {
        if (instance == null) {
            instance = new SessionManager(context);
        }
        return instance;
    }

    /**
     * Returns all saved sessions
     */
    public List<DesignSession> getAllSessions() {
        return new ArrayList<>(sessions);
    }

    /**
     * Gets a session by ID
     */
    public DesignSession getSessionById(String sessionId) {
        for (DesignSession session : sessions) {
            if (session.getId().equals(sessionId)) {
                return session;
            }
        }
        return null;
    }

    /**
     * Adds a new session and saves it to storage
     */
    public void addSession(DesignSession session) {
        sessions.add(session);
        saveSessions();
    }

    /**
     * Updates an existing session and saves it to storage
     */
    public void updateSession(DesignSession updatedSession) {
        for (int i = 0; i < sessions.size(); i++) {
            if (sessions.get(i).getId().equals(updatedSession.getId())) {
                sessions.set(i, updatedSession);
                break;
            }
        }
        saveSessions();
    }

    /**
     * Deletes a session
     */
    public void deleteSession(String sessionId) {
        sessions.removeIf(session -> session.getId().equals(sessionId));
        saveSessions();
    }

    /**
     * Loads sessions from storage
     */
    private void loadSessions() {
        File sessionsFile = getSessionsFile();

        if (!sessionsFile.exists()) {
            Log.d(TAG, "Sessions file does not exist yet");
            return;
        }

        try (BufferedReader reader = new BufferedReader(new FileReader(sessionsFile))) {
            StringBuilder jsonString = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                jsonString.append(line);
            }

            JSONArray jsonArray = new JSONArray(jsonString.toString());
            sessions.clear();

            for (int i = 0; i < jsonArray.length(); i++) {
                JSONObject sessionJson = jsonArray.getJSONObject(i);
                DesignSession session = DesignSession.fromJson(sessionJson);
                sessions.add(session);
            }

            Log.d(TAG, "Loaded " + sessions.size() + " sessions");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error loading sessions", e);
        }
    }

    /**
     * Saves sessions to storage
     */
    private void saveSessions() {
        File sessionsFile = getSessionsFile();

        try {
            // Ensure directory exists
            File parent = sessionsFile.getParentFile();
            if (!parent.exists()) {
                if (!parent.mkdirs()) {
                    Log.e(TAG, "Could not create sessions directory");
                    return;
                }
            }

            JSONArray jsonArray = new JSONArray();
            for (DesignSession session : sessions) {
                jsonArray.put(session.toJson());
            }

            try (BufferedWriter writer = new BufferedWriter(new FileWriter(sessionsFile))) {
                writer.write(jsonArray.toString());
            }

            Log.d(TAG, "Saved " + sessions.size() + " sessions");
        } catch (IOException | JSONException e) {
            Log.e(TAG, "Error saving sessions", e);
        }
    }

    /**
     * Gets the file where sessions are stored
     */
    private File getSessionsFile() {
        File dir = new File(context.getFilesDir(), SESSIONS_DIR);
        return new File(dir, SESSIONS_FILE);
    }

    /**
     * Asynchronously loads sessions in the background
     */
    public CompletableFuture<List<DesignSession>> loadSessionsAsync() {
        return CompletableFuture.supplyAsync(() -> {
            loadSessions();
            return getAllSessions();
        });
    }
}