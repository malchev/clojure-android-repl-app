package com.example.clojurerepl;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.clojurerepl.session.DesignSession;
import com.example.clojurerepl.session.DesignSessionAdapter;
import com.example.clojurerepl.session.SessionManager;

import java.util.ArrayList;
import java.util.List;

public class DesignSessionsActivity extends AppCompatActivity implements DesignSessionAdapter.SessionClickListener {
    private static final String TAG = "DesignSessionsActivity";

    private RecyclerView recyclerView;
    private Button newSessionButton;
    private DesignSessionAdapter adapter;
    private SessionManager sessionManager;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_design_sessions);

        // Initialize views
        recyclerView = findViewById(R.id.sessions_recycler_view);
        newSessionButton = findViewById(R.id.new_session_button);

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Get session manager
        sessionManager = SessionManager.getInstance(this);

        // Initialize adapter with empty list (will be populated in onResume)
        adapter = new DesignSessionAdapter(this, new ArrayList<>(), this);
        recyclerView.setAdapter(adapter);

        // Set up new session button
        newSessionButton.setOnClickListener(v -> startNewDesignSession());
    }

    @Override
    protected void onResume() {
        super.onResume();
        loadSessions();
    }

    private void loadSessions() {
        // Show progress indicator if needed

        // Load sessions asynchronously
        sessionManager.loadSessionsAsync()
                .thenAccept(sessions -> {
                    runOnUiThread(() -> {
                        adapter.updateSessions(sessions);
                        if (sessions.isEmpty()) {
                            Toast.makeText(this, "No saved sessions found", Toast.LENGTH_SHORT).show();
                        }
                    });
                })
                .exceptionally(e -> {
                    Log.e(TAG, "Error loading sessions", e);
                    runOnUiThread(() -> {
                        Toast.makeText(this, "Error loading sessions", Toast.LENGTH_SHORT).show();
                    });
                    return null;
                });
    }

    private void startNewDesignSession() {
        Intent intent = new Intent(this, ClojureAppDesignActivity.class);
        startActivity(intent);
    }

    @Override
    public void onOpenSessionClicked(DesignSession session) {
        Intent intent = new Intent(this, ClojureAppDesignActivity.class);
        intent.putExtra("session_id", session.getId());
        startActivity(intent);
    }

    @Override
    public void onDeleteSessionClicked(DesignSession session) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Session")
                .setMessage("Are you sure you want to delete this session?\n\n" + session.getDisplaySummary())
                .setPositiveButton("Delete", (dialog, which) -> {
                    sessionManager.deleteSession(session.getId());
                    loadSessions(); // Reload the list
                    Toast.makeText(this, "Session deleted", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}