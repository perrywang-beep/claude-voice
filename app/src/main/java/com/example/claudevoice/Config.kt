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

    const val SYSTEM_PROMPT = """你是语音助手，风格极简、直接。
规则：
- 直接给结论/答案，不要开场白（禁止"好的""当然""我来为你"等）
- 只说最关键的点，不展开、不举例、不解释原因
- 可以1-3句，但每句必须有实质内容，不能废话
- 禁止markdown、禁止列表符号"""

    const val MAX_TOKENS = 400

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
    const val TTS_VOICE   = "BV002_streaming"   // 标准男声
    const val TTS_SPEED   = 1.15                // 语速倍率，1.0=正常，>1=加速
    const val TTS_URL     = "https://openspeech.bytedance.com/api/v1/tts"

    // ===== 语音识别（ASR）=====
    // 与 TTS 共用同一个 AppID/Token；需在控制台开通「语音识别」服务
    // https://console.volcengine.com/speech/service/16
    // cluster 名称在控制台「语音识别 → 我的接入点」中查看，通常为 volcengine_input_common
    const val ASR_CLUSTER = "volcengine_input_common"
    const val ASR_URL     = "https://openspeech.bytedance.com/api/v1/asr"
}
