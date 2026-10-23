package ai.guiji.duix.test.model

import ai.guiji.duix.test.R

object AvatarCatalog {

    val models = listOf(
        AvatarModel("晨煦", "Ethan", "https://dl.xn--876a.net/models/bendi3_20240518.zip", R.mipmap.ethan),
        AvatarModel("詹妮弗", "Maia", "https://dl.xn--876a.net/models/Emma.zip", R.mipmap.jennifer),
        AvatarModel("月白", "Moon", "https://dl.xn--876a.net/models/Kai.zip", R.mipmap.moon),
        AvatarModel("艾登", "Aiden", "https://dl.xn--876a.net/models/Leo.zip", R.mipmap.leo),
        AvatarModel("四月", "Serena", "https://dl.xn--876a.net/models/Lily.zip", R.mipmap.lily),
        AvatarModel("奥利弗", "Andre", "https://dl.xn--876a.net/models/Oliver.zip", R.mipmap.oliver),
        AvatarModel("索菲亚", "Sonrisa", "https://dl.xn--876a.net/models/Sofia.zip", R.mipmap.sofia),
    )

    fun findByModelUrl(modelUrl: String?): AvatarModel? {
        return models.firstOrNull { it.modelUrl == modelUrl }
    }
}