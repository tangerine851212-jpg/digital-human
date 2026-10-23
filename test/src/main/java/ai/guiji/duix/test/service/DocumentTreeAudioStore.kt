package ai.guiji.duix.test.service

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.util.Locale

object DocumentTreeAudioStore {

    data class AudioEntry(
        val uri: Uri,
        val displayName: String,
        val relativePath: String,
    )

    data class ScanResult(
        val rootName: String,
        val files: List<AudioEntry>,
    )

    fun scanAudioFiles(context: Context, treeUri: Uri): ScanResult {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("无法读取所选文件夹")
        if (!root.isDirectory) {
            throw IllegalArgumentException("所选内容不是文件夹")
        }

        val files = mutableListOf<AudioEntry>()
        traverse(root, "", files)
        return ScanResult(
            rootName = root.name ?: "selected-folder",
            files = files.sortedBy { it.relativePath.lowercase(Locale.US) },
        )
    }

    fun writeVideoResult(
        context: Context,
        treeUri: Uri,
        outputRootName: String,
        sourceRelativePath: String,
        localFile: File,
    ): Uri {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: throw IllegalArgumentException("无法写入所选文件夹")
        if (!root.isDirectory) {
            throw IllegalArgumentException("所选内容不是文件夹")
        }

        var currentDir = findOrCreateDirectory(root, outputRootName)
        val normalizedPath = sourceRelativePath.replace('\\', '/')
        val pathSegments = normalizedPath.split('/').filter { it.isNotBlank() }
        if (pathSegments.isEmpty()) {
            throw IllegalArgumentException("无效的源文件路径")
        }

        for (segment in pathSegments.dropLast(1)) {
            currentDir = findOrCreateDirectory(currentDir, segment)
        }

        val outputFileName = replaceExtension(pathSegments.last(), "mp4")
        currentDir.findFile(outputFileName)?.delete()
        val outputDocument = currentDir.createFile(VIDEO_MIME_TYPE, outputFileName)
            ?: throw IllegalStateException("无法创建输出文件")

        localFile.inputStream().use { input ->
            context.contentResolver.openOutputStream(outputDocument.uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("无法打开输出文件")
        }
        return outputDocument.uri
    }

    fun buildOutputRelativePath(outputRootName: String, sourceRelativePath: String): String {
        val normalizedPath = sourceRelativePath.replace('\\', '/')
        return outputRootName + "/" + replaceExtension(normalizedPath, "mp4")
    }

    private fun traverse(directory: DocumentFile, parentPath: String, output: MutableList<AudioEntry>) {
        val children = directory.listFiles().sortedBy { it.name?.lowercase(Locale.US) ?: "" }
        for (child in children) {
            val childName = child.name ?: continue
            val childRelativePath = if (parentPath.isEmpty()) childName else "$parentPath/$childName"
            when {
                child.isDirectory -> traverse(child, childRelativePath, output)
                child.isFile && isAudioFile(child) -> {
                    output += AudioEntry(
                        uri = child.uri,
                        displayName = childName,
                        relativePath = childRelativePath,
                    )
                }
            }
        }
    }

    private fun isAudioFile(document: DocumentFile): Boolean {
        val mimeType = document.type?.lowercase(Locale.US).orEmpty()
        if (mimeType.startsWith("audio/")) {
            return true
        }
        val name = document.name?.lowercase(Locale.US).orEmpty()
        return AUDIO_EXTENSIONS.any { extension -> name.endsWith(".$extension") }
    }

    private fun findOrCreateDirectory(parent: DocumentFile, name: String): DocumentFile {
        parent.findFile(name)?.let { existing ->
            if (existing.isDirectory) {
                return existing
            }
            existing.delete()
        }
        return parent.createDirectory(name)
            ?: throw IllegalStateException("无法创建目录: $name")
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

    private const val VIDEO_MIME_TYPE = "video/mp4"
}