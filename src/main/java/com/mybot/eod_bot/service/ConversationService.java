package com.mybot.eod_bot.service; // <-- CORRECTED PACKAGE

import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages the "state" of a chat conversation in memory.
 * This allows the bot to ask multi-step questions.
 */
@Service
public class ConversationService {
    // Map<ChatID, State> (e.g., "AWAIT_DIST_NAME")
    private final Map<Long, String> userState = new ConcurrentHashMap<>();
    // Map<ChatID, TemporaryObject> (e.g., a half-built Distributor)
    private final Map<Long, Object> userTempData = new ConcurrentHashMap<>();

    public void setState(long chatId, String state) { userState.put(chatId, state); }
    public String getState(long chatId) { return userState.get(chatId); }
    public void clearState(long chatId) {
        userState.remove(chatId);
        userTempData.remove(chatId);
    }
    public void setTempData(long chatId, Object data) { userTempData.put(chatId, data); }

    @SuppressWarnings("unchecked")
    public <T> T getTempData(long chatId, Class<T> type) {
        return (T) userTempData.get(chatId);
    }
}
