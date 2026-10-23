package ai.guiji.duix.test.model

import android.net.Uri

data class BatchAudioJob(
    val sourceUri: Uri,
    val sourceRelativePath: String,
    val displayName: String,
    val outputRelativePath: String,
    val attemptCount: Int = 0,
    val status: Status = Status.PENDING,
    val statusText: String = "",
    val detailText: String = "",
    val outputUri: Uri? = null,
) {
    enum class Status {
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        SKIPPED,
    }
}