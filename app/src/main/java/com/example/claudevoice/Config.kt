package com.example.claudevoice

/**
 * 全局配置。修改后重新编译安装即可生效。
 */
object Config {

    // ===== 火山方舟 API Key =====
    // 来源：https://console.volcengine.com/ark → API Key 管理
    const val ARK_API_KEY = "ark-50c8a143-076c-4506-a795-144d3965a6c4-d98bb"

    // ===== 火山方舟推理接入点 ID =====
    // 模型：doubao-1.5-pro-32k
    const val ARK_ENDPOINT_ID = "ep-20260527140749-7zcnv"

    // ===== 对话配置 =====

    const val SYSTEM_PROMPT = "你是一个语音助手，回答简洁，适合听觉接收，不要用 markdown 格式。" +
            "回答时直接说内容，不要有多余的开场白。"

    const val MAX_TOKENS = 1024

    // ===== 对话历史 =====

    const val MAX_HISTORY_TURNS = 10

    // ===== 触发阈值 =====

    const val VOLUME_DOWN_LONG_PRESS_MS = 800L
    const val VOLUME_UP_LONG_PRESS_MS   = 2000L

    // ===== 通知 =====

    const val NOTIFICATION_ID         = 1001
    const val NOTIFICATION_CHANNEL_ID = "claude_voice_channel"

    // ===== Intent Action =====

    const val ACTION_TRIGGER_VOICE = "com.example.claudevoice.TRIGGER_VOICE"
    const val ACTION_CLEAR_CONTEXT = "com.example.claudevoice.CLEAR_CONTEXT"

    // ===== 火山方舟 API =====

    const val ARK_URL = "https://ark.cn-beijing.volces.com/api/v3/chat/completions"

    // ===== 语音合成（语音技术产品）=====
    // 来源：https://console.volcengine.com/speech/service/8

    const val TTS_APP_ID  = "7696822959"
    const val TTS_TOKEN   = "PDlZu0111x_8YhbchuPjvnQBwjnFhSJ0"
    const val TTS_CLUSTER = "volcano_tts"
    const val TTS_VOICE   = "zh_female_tianmei_saas"
    const val TTS_URL     = "https://openspeech.bytedance.com/api/v1/tts"
}
