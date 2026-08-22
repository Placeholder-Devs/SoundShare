package com.example.soundshare;

import android.annotation.SuppressLint;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adapter for displaying the synchronized multi-track playback queue with drag-to-reorder support.
 */
public class FileQueueAdapter extends RecyclerView.Adapter<FileQueueAdapter.ViewHolder> {

    public interface OnStartDragListener {
        void onStartDrag(RecyclerView.ViewHolder viewHolder);
    }

    public interface OnQueueItemListener {
        void onTrackSelected(int position, FileQueueItem item);
        void onTrackRemoved(int position, FileQueueItem item);
        void onTrackMoved(int fromPosition, int toPosition);
        void onTrackDownload(int position, FileQueueItem item);
    }

    private final List<FileQueueItem> items = new ArrayList<>();
    private final OnQueueItemListener listener;
    private OnStartDragListener dragListener;
    private int currentPlayingIndex = -1;

    public FileQueueAdapter(OnQueueItemListener listener) {
        this.listener = listener;
    }

    public void setOnStartDragListener(OnStartDragListener dragListener) {
        this.dragListener = dragListener;
    }

    public void setItems(List<FileQueueItem> newItems) {
        items.clear();
        if (newItems != null) items.addAll(newItems);
        notifyDataSetChanged();
    }

    public void addItem(FileQueueItem item) {
        items.add(item);
        notifyItemInserted(items.size() - 1);
    }

    public void removeItem(int position) {
        if (position >= 0 && position < items.size()) {
            items.remove(position);
            notifyItemRemoved(position);
            notifyItemRangeChanged(position, items.size() - position);
        }
    }

    public void moveItem(int fromPosition, int toPosition) {
        if (fromPosition < 0 || fromPosition >= items.size() ||
            toPosition < 0 || toPosition >= items.size()) return;

        if (fromPosition < toPosition) {
            for (int i = fromPosition; i < toPosition; i++) {
                Collections.swap(items, i, i + 1);
            }
        } else {
            for (int i = fromPosition; i > toPosition; i--) {
                Collections.swap(items, i, i - 1);
            }
        }

        // Update currentPlayingIndex if affected
        if (currentPlayingIndex == fromPosition) {
            currentPlayingIndex = toPosition;
        } else if (fromPosition < currentPlayingIndex && toPosition >= currentPlayingIndex) {
            currentPlayingIndex--;
        } else if (fromPosition > currentPlayingIndex && toPosition <= currentPlayingIndex) {
            currentPlayingIndex++;
        }

        notifyItemMoved(fromPosition, toPosition);
        notifyItemRangeChanged(Math.min(fromPosition, toPosition), Math.abs(fromPosition - toPosition) + 1);

        if (listener != null) {
            listener.onTrackMoved(fromPosition, toPosition);
        }
    }

    public void setCurrentPlayingIndex(int index) {
        int prev = currentPlayingIndex;
        currentPlayingIndex = index;
        if (prev >= 0 && prev < items.size()) notifyItemChanged(prev);
        if (currentPlayingIndex >= 0 && currentPlayingIndex < items.size()) {
            notifyItemChanged(currentPlayingIndex);
        }
    }

    public int getCurrentPlayingIndex() {
        return currentPlayingIndex;
    }

    public List<FileQueueItem> getItems() {
        return items;
    }

    public FileQueueItem getItem(int position) {
        if (position >= 0 && position < items.size()) return items.get(position);
        return null;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_queue_song, parent, false);
        return new ViewHolder(v);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        FileQueueItem item = items.get(position);
        holder.tvTrackNumber.setText(String.valueOf(position + 1));
        holder.tvQueueTitle.setText(item.getFileName());

        String details = (!item.getArtist().isEmpty() && !"Unknown Artist".equals(item.getArtist())
                ? item.getArtist() + " • " : "")
                + item.getDurationStr() + (item.getDurationStr().isEmpty() ? "" : " • ")
                + item.getFormatDesc();
        holder.tvQueueDetails.setText(details);

        boolean isPlaying = (position == currentPlayingIndex);
        if (isPlaying) {
            holder.tvTrackNumber.setTextColor(Color.parseColor("#50E3C2")); // Cyan
            holder.tvQueueTitle.setTextColor(Color.parseColor("#50E3C2"));
            holder.tvQueueStatus.setText("Playing");
            holder.tvQueueStatus.setTextColor(Color.parseColor("#50E3C2"));
            holder.tvQueueStatus.setVisibility(View.VISIBLE);
        } else {
            holder.tvTrackNumber.setTextColor(Color.parseColor("#9D00FF"));
            holder.tvQueueTitle.setTextColor(Color.parseColor("#F0E8F5"));
            holder.tvQueueStatus.setText(item.isReady() ? "Ready" : "Syncing");
            holder.tvQueueStatus.setTextColor(item.isReady() ? Color.parseColor("#8A7A9A") : Color.parseColor("#FF8800"));
            holder.tvQueueStatus.setVisibility(View.VISIBLE);
        }

        holder.ivDragHandle.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (dragListener != null) {
                    dragListener.onStartDrag(holder);
                }
            }
            return false;
        });

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onTrackSelected(holder.getAdapterPosition(), item);
        });

        holder.btnDownloadQueueItem.setOnClickListener(v -> {
            if (listener != null) listener.onTrackDownload(holder.getAdapterPosition(), item);
        });

        holder.btnRemoveQueueItem.setOnClickListener(v -> {
            if (listener != null) listener.onTrackRemoved(holder.getAdapterPosition(), item);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final ImageView ivDragHandle;
        final TextView tvTrackNumber, tvQueueTitle, tvQueueDetails, tvQueueStatus;
        final ImageButton btnDownloadQueueItem, btnRemoveQueueItem;

        ViewHolder(@NonNull View itemView) {
            super(itemView);
            ivDragHandle = itemView.findViewById(R.id.ivDragHandle);
            tvTrackNumber = itemView.findViewById(R.id.tvTrackNumber);
            tvQueueTitle = itemView.findViewById(R.id.tvQueueTitle);
            tvQueueDetails = itemView.findViewById(R.id.tvQueueDetails);
            tvQueueStatus = itemView.findViewById(R.id.tvQueueStatus);
            btnDownloadQueueItem = itemView.findViewById(R.id.btnDownloadQueueItem);
            btnRemoveQueueItem = itemView.findViewById(R.id.btnRemoveQueueItem);
        }
    }
}
