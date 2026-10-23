package ai.guiji.duix.test.model

/**
 * 一个年龄段对应的对话配置。
 *
 * 这里只描述“怎么聊”，不绑定任何数字人形象或模型地址。
 */
data class AudienceProfile(
    val segment: AudienceSegment,
    val displayName: String,
    val ageRange: String,
    val defaultAddress: String,
    val tonePrompt: String,
    val knowledgeAsset: String,
    val safetyPolicy: String,
    val suggestedTopics: List<String>
)
