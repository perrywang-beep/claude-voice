package com.example.claudevoice.model

import com.example.claudevoice.Config
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * 对话历史管理器。
 *
 * 保留最近 [Config.MAX_HISTORY_TURNS] 轮对话（每轮 = 1条用户消息 + 1条助手消息）。
 * 线程安全（synchronized）。
 */
class ConversationHistory {

    data class Message(
        val role: String,          // "user" 或 "assistant"
        val content: String        // 文本内容
    )

    private val history = ArrayDeque<Message>()

    @Synchronized
    fun addUserMessage(text: String) {
        history.addLast(Message("user", text))
        trim()
    }

    @Synchronized
    fun addAssistantMessage(text: String) {
        history.addLast(Message("assistant", text))
        trim()
    }

    @Synchronized
    fun clear() {
        history.clear()
    }

    @Synchronized
    fun size(): Int = history.size

    /**
     * 将历史转为 Anthropic Messages API 需要的 JSON 数组格式。
     * 仅包含历史消息，不含当前轮次（当前轮次由调用者在末尾追加）。
     */
    @Synchronized
    fun toJsonArray(): JSONArray {
        val arr = JSONArray()
        for (msg in history) {
            val obj = JSONObject()
            obj.put("role", msg.role)
            obj.put("content", msg.content)
            arr.put(obj)
        }
        return arr
    }

    /**
     * 当历史超过最大轮数限制时，从队头移除最早的一对消息（用户+助手）。
     * MAX_HISTORY_TURNS 轮 = MAX_HISTORY_TURNS * 2 条消息。
     */
    private fun trim() {
        val maxMessages = Config.MAX_HISTORY_TURNS * 2
        while (history.size > maxMessages) {
            history.pollFirst()  // 移除最旧的用户消息
            if (history.isNotEmpty()) history.pollFirst()  // 移除对应助手消息
        }
    }
}
