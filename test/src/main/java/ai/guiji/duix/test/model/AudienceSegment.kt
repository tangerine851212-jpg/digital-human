package ai.guiji.duix.test.model

/**
 * 对话人群模式。只影响对话语气、称呼、知识包和安全策略，
 * 不影响数字人形象、音色或 DUIX 模型。
 */
enum class AudienceSegment(val storageKey: String) {
    TEEN("teen"),
    ADULT("adult"),
    SENIOR("senior");

    companion object {
        fun fromStorageKey(value: String?): AudienceSegment? {
            return values().firstOrNull { it.storageKey == value }
        }
    }
}
