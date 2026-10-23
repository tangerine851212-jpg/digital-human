package ai.guiji.duix.test.model

/**
 * 数字人形象模型
 * @param name 中文显示名
 * @param voice 语音音色名（传给 Omni Realtime）
 * @param modelUrl 模型资源下载地址
 * @param avatarRes 头像资源 ID（mipmap）
 */
data class AvatarModel(
    val name: String,
    val voice: String,
    val modelUrl: String,
    val avatarRes: Int
)
