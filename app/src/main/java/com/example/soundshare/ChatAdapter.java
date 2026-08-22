package com.example.soundshare;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private final List<ChatMessage> messages = new ArrayList<>();

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    public int getLastPosition() {
        return messages.size() - 1;
    }

    public void clear() {
        messages.clear();
        notifyDataSetChanged();
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).getType();
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_chat_message, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        holder.bind(messages.get(position));
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class ChatViewHolder extends RecyclerView.ViewHolder {
        private final LinearLayout bubbleContainer;
        private final LinearLayout chatBubble;
        private final TextView tvSender;
        private final TextView tvMessage;
        private final TextView tvTime;

        ChatViewHolder(@NonNull View itemView) {
            super(itemView);
            bubbleContainer = itemView.findViewById(R.id.bubbleContainer);
            chatBubble = itemView.findViewById(R.id.chatBubble);
            tvSender = itemView.findViewById(R.id.tvChatSender);
            tvMessage = itemView.findViewById(R.id.tvChatMessage);
            tvTime = itemView.findViewById(R.id.tvChatTime);
        }

        void bind(ChatMessage msg) {
            tvMessage.setText(msg.getMessage());
            tvTime.setText(msg.getFormattedTime());

            tvSender.setVisibility(View.VISIBLE);
            tvTime.setVisibility(View.VISIBLE);
            tvMessage.setTextAlignment(View.TEXT_ALIGNMENT_TEXT_START);

            switch (msg.getType()) {
                case ChatMessage.TYPE_SENT:
                    bubbleContainer.setGravity(Gravity.END);
                    chatBubble.setBackgroundResource(R.drawable.bg_chat_sent);
                    tvSender.setText("You");
                    tvSender.setTextColor(0xBBFFFFFF);
                    tvMessage.setTextColor(0xFFFFFFFF);
                    tvTime.setTextColor(0x88FFFFFF);
                    break;

                case ChatMessage.TYPE_RECEIVED:
                    bubbleContainer.setGravity(Gravity.START);
                    chatBubble.setBackgroundResource(R.drawable.bg_chat_received);
                    tvSender.setText(msg.getSender());
                    tvSender.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.accent_green));
                    tvMessage.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.text_primary));
                    tvTime.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.text_hint));
                    break;

                case ChatMessage.TYPE_SYSTEM:
                    bubbleContainer.setGravity(Gravity.CENTER);
                    chatBubble.setBackgroundResource(R.drawable.bg_chat_system);
                    tvSender.setVisibility(View.GONE);
                    tvTime.setVisibility(View.GONE);
                    tvMessage.setTextColor(ContextCompat.getColor(
                            itemView.getContext(), R.color.text_hint));
                    tvMessage.setTextAlignment(View.TEXT_ALIGNMENT_CENTER);
                    break;
            }
        }
    }
}