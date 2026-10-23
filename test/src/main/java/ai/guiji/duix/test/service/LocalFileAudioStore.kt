package ai.guiji.duix.test.service

import android.net.Uri
import java.io.File
import java.util.Locale

object LocalFileAudioStore {

    data class AudioEntry(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
    )

    data class ScanResult(
        val rootDir: File,
        val rootName: String,
        val files: List<AudioEntry>,
    )

    data class WriteResult(
        val file: File,
        val skipped: Boolean,
    )

    fun scanAudioFiles(rootDir: File): ScanResult {
        val normalizedRoot = rootDir.canonicalFile
        if (!normalizedRoot.exists() || !normalizedRoot.isDirectory) {
            throw IllegalArgumentException("无法读取所选文件夹")
        }

        val files = mutableListOf<AudioEntry>()
        traverse(normalizedRoot, "", files)
        return ScanResult(
            rootDir = normalizedRoot,
            rootName = normalizedRoot.name.ifBlank { normalizedRoot.absolutePath },
            files = files.sortedBy { it.relativePath.lowercase(Locale.US) },
        )
    }

    fun writeVideoResult(
        rootDir: File,
        outputRootName: String,
        sourceRelativePath: String,
        localFile: File,
    ): WriteResult {
        val normalizedRoot = rootDir.canonicalFile
        if (!normalizedRoot.exists() || !normalizedRoot.isDirectory) {
            throw IllegalArgumentException("无法写入所选文件夹")
        }

        val outputFile = resolveOutputFile(normalizedRoot, outputRootName, sourceRelativePath)
        if (outputFile.exists()) {
            return WriteResult(outputFile, skipped = true)
        }
        outputFile.parentFile?.let { parent ->
            if (!parent.exists() && !parent.mkdirs()) {
                throw IllegalStateException("无法创建输出目录")
            }
        }
        return runCatching {
            localFile.copyTo(outputFile, overwrite = false)
            WriteResult(outputFile, skipped = false)
        }.getOrElse { error ->
            if (outputFile.exists()) {
                WriteResult(outputFile, skipped = true)
            } else {
                throw error
            }
        }
    }

    fun resolveOutputFile(
        rootDir: File,
        outputRootName: String,
        sourceRelativePath: String,
    ): File {
        val normalizedRoot = rootDir.canonicalFile
        return File(normalizedRoot, buildOutputRelativePath(outputRootName, sourceRelativePath))
    }

    fun buildOutputRelativePath(outputRootName: String, sourceRelativePath: String): String {
        val normalizedPath = sourceRelativePath.replace('\\', '/')
        return outputRootName + "/" + replaceExtension(normalizedPath, "mp4")
    }

    private fun traverse(directory: File, parentPath: String, output: MutableList<AudioEntry>) {
        val children = directory.listFiles()
            ?.sortedBy { it.name.lowercase(Locale.US) }
            .orEmpty()
        for (child in children) {
            val childRelativePath = if (parentPath.isEmpty()) child.name else "$parentPath/${child.name}"
            when {
                child.isDirectory -> traverse(child, childRelativePath, output)
                child.isFile && isAudioFile(child) -> {
                    output += AudioEntry(
                        uri = Uri.fromFile(child),
                        displayName = child.name,
                        relativePath = childRelativePath,
                    )
                }
            }
        }
    }

    private fun isAudioFile(file: File): Boolean {
        val extension = file.extension.lowercase(Locale.US)
        return extension in AUDIO_EXTENSIONS
    }

    private fun replaceExtension(name: String, extension: String): String {
        val dotIndex = name.lastIndexOf('.')
        val baseName = if (dotIndex > 0) name.substring(0, dotIndex) else name
        return "$baseName.$extension"
    }

    private val AUDIO_EXTENSIONS = setOf(
        "aac",
        "amr",
        "flac",
        "m4a",
        "mp3",
        "ogg",
        "opus",
        "wav",
        "webm",
        "wma",
        "3gp",
    )
}