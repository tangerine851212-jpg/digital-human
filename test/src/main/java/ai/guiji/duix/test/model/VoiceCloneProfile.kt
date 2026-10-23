package ai.guiji.duix.test.model

data class VoiceCloneProfile(
    val voice: String,
    val targetModel: String,
    val preferredName: String,
    val samplePath: String,
    val avatarName: String,
    val avatarVoice: String,
    val avatarModelUrl: String,
    val avatarRes: Int,
    val createdAt: Long
) {
    fun toAvatarModel(): AvatarModel {
        return AvatarModel(
            name = avatarName,
            voice = avatarVoice,
            modelUrl = avatarModelUrl,
            avatarRes = avatarRes
        )
    }
}