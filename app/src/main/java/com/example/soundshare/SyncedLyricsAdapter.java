package com.example.soundshare;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class SyncedLyricsAdapter extends RecyclerView.Adapter<SyncedLyricsAdapter.LyricViewHolder> {
    private final List<LyricsHelper.LyricLine> lines = new ArrayList<>();
    private int activeIndex = -1;

    public void setLines(List<LyricsHelper.LyricLine> newLines) {
        lines.clear();
        if (newLines != null) lines.addAll(newLines);
        activeIndex = -1;
        notifyDataSetChanged();
    }

    public boolean updateActiveIndex(int newIndex, RecyclerView rv) {
        if (newIndex == activeIndex) return false;
        int prev = activeIndex;
        activeIndex = newIndex;
        if (prev >= 0 && prev < lines.size()) notifyItemChanged(prev);
        if (activeIndex >= 0 && activeIndex < lines.size()) {
            notifyItemChanged(activeIndex);
            if (rv != null) {
                rv.smoothScrollToPosition(activeIndex);
            }
        }
        return true;
    }

    @NonNull
    @Override
    public LyricViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_synced_lyric, parent, false);
        return new LyricViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull LyricViewHolder holder, int position) {
        LyricsHelper.LyricLine item = lines.get(position);
        holder.tvText.setText(item.text);

        if (position == activeIndex) {
            holder.tvText.setTextColor(Color.WHITE);
            holder.tvText.setTextSize(16.5f);
            holder.tvText.setTypeface(null, Typeface.BOLD);
            holder.tvText.setAlpha(1.0f);
        } else {
            holder.tvText.setTextColor(Color.parseColor("#94A3B8"));
            holder.tvText.setTextSize(14f);
            holder.tvText.setTypeface(null, Typeface.NORMAL);
            holder.tvText.setAlpha(0.6f);
        }
    }

    @Override
    public int getItemCount() {
        return lines.size();
    }

    static class LyricViewHolder extends RecyclerView.ViewHolder {
        final TextView tvText;

        LyricViewHolder(View itemView) {
            super(itemView);
            tvText = itemView.findViewById(R.id.tvLyricLineText);
        }
    }
}
