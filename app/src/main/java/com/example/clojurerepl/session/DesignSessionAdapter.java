package com.example.clojurerepl.session;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.clojurerepl.R;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class DesignSessionAdapter extends RecyclerView.Adapter<DesignSessionAdapter.SessionViewHolder> {

    private List<DesignSession> sessions;
    private Context context;
    private SessionClickListener listener;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

    public interface SessionClickListener {
        void onOpenSessionClicked(DesignSession session);

        void onDeleteSessionClicked(DesignSession session);
    }

    public DesignSessionAdapter(Context context, List<DesignSession> sessions, SessionClickListener listener) {
        this.context = context;
        this.sessions = sessions;
        this.listener = listener;
    }

    @NonNull
    @Override
    public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_design_session, parent, false);
        return new SessionViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
        DesignSession session = sessions.get(position);

        holder.descriptionTextView.setText(session.getDescription());
        holder.dateTextView.setText("Created: " + dateFormat.format(session.getCreatedAt()));
        holder.llmInfoTextView.setText(session.getLlmType() + " (" + session.getLlmModel() + ")");
        holder.iterationsTextView.setText("Iterations: " + session.getIterationCount());

        holder.openButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onOpenSessionClicked(session);
            }
        });

        holder.deleteButton.setOnClickListener(v -> {
            if (listener != null) {
                listener.onDeleteSessionClicked(session);
            }
        });
    }

    @Override
    public int getItemCount() {
        return sessions != null ? sessions.size() : 0;
    }

    public void updateSessions(List<DesignSession> newSessions) {
        this.sessions = newSessions;
        notifyDataSetChanged();
    }

    static class SessionViewHolder extends RecyclerView.ViewHolder {
        TextView descriptionTextView;
        TextView dateTextView;
        TextView llmInfoTextView;
        TextView iterationsTextView;
        Button openButton;
        Button deleteButton;

        public SessionViewHolder(@NonNull View itemView) {
            super(itemView);
            descriptionTextView = itemView.findViewById(R.id.session_description);
            dateTextView = itemView.findViewById(R.id.session_date);
            llmInfoTextView = itemView.findViewById(R.id.session_llm_info);
            iterationsTextView = itemView.findViewById(R.id.session_iterations);
            openButton = itemView.findViewById(R.id.open_session_button);
            deleteButton = itemView.findViewById(R.id.delete_session_button);
        }
    }
}