package com.example.clojurerepl.session;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.example.clojurerepl.R;

import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Locale;

public class DesignSessionAdapter extends ListAdapter<DesignSession, DesignSessionAdapter.SessionViewHolder> {

    private Context context;
    private SessionClickListener listener;
    private SimpleDateFormat dateFormat = new SimpleDateFormat("MMM dd, yyyy HH:mm", Locale.getDefault());

    public interface SessionClickListener {
        void onOpenSessionClicked(DesignSession session);

        void onDeleteSessionClicked(DesignSession session);
    }

    public DesignSessionAdapter(Context context, SessionClickListener listener) {
        super(new DiffUtil.ItemCallback<DesignSession>() {
            @Override
            public boolean areItemsTheSame(@NonNull DesignSession oldItem, @NonNull DesignSession newItem) {
                return oldItem.getId().equals(newItem.getId());
            }

            @Override
            public boolean areContentsTheSame(@NonNull DesignSession oldItem, @NonNull DesignSession newItem) {
                // Always return false to force UI updates
                // This ensures the UI always reflects the latest data from storage
                return false;
            }
        });
        this.context = context;
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
        DesignSession session = getItem(position);

        holder.descriptionTextView.setText(session.getDisplaySummary());
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
