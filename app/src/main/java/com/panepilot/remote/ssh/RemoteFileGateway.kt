package com.panepilot.remote.ssh

import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.SftpProgressMonitor
import com.panepilot.remote.model.RemoteFileEntry
import com.panepilot.remote.model.RemoteFilePreview
import com.panepilot.remote.model.RemoteFilePreviewKind
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Vector

data class RemoteFileLocation(
    val directoryRelativePath: String,
    val selectedFileRelativePath: String?
)

class RemoteFileGateway(private val ssh: SshConnection) {
    fun list(rootPath: String, relativePath: String): List<RemoteFileEntry> {
        val normalized = normalizeRelativePath(relativePath)
        return ssh.withSftp { sftp ->
            val root = canonicalRoot(sftp, rootPath)
            val directory = resolveWithinRoot(sftp, root, normalized)
            val attributes = sftp.stat(directory)
            require(attributes.isDir) { "This remote path is not a folder." }

            @Suppress("UNCHECKED_CAST")
            val entries = sftp.ls(directory) as Vector<ChannelSftp.LsEntry>
            entries.asSequence()
                .filter { it.filename != "." && it.filename != ".." }
                .filter { entry -> safeEntry(entry) }
                .take(MAX_DIRECTORY_ENTRIES)
                .map { entry ->
                    val childRelativePath = joinRelative(normalized, entry.filename)
                    RemoteFileEntry(
                        name = entry.filename,
                        relativePath = childRelativePath,
                        isDirectory = entry.attrs.isDir,
                        sizeBytes = entry.attrs.size.coerceAtLeast(0L),
                        modifiedAtMillis = entry.attrs.mTime.toLong() * 1_000L
                    )
                }
                .sortedWith(
                    compareByDescending<RemoteFileEntry> { it.isDirectory }
                        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                )
                .toList()
        }
    }

    fun locate(rootPath: String, reference: String): RemoteFileLocation {
        val cleanedReference = sanitizeReference(reference)
        return ssh.withSftp { sftp ->
            val root = canonicalRoot(sftp, rootPath)
            val unresolved = if (cleanedReference.startsWith('/')) {
                cleanedReference
            } else {
                val relative = normalizeRelativePath(cleanedReference.removePrefix("./"))
                if (root == "/") "/$relative" else "$root/$relative"
            }
            val resolved = sftp.realpath(unresolved).trimEnd('/').ifEmpty { "/" }
            require(isWithinRoot(root, resolved)) {
                "The selected path leaves the project folder."
            }
            val attributes = sftp.stat(resolved)
            val relative = relativeToRoot(root, resolved)
            if (attributes.isDir) {
                RemoteFileLocation(
                    directoryRelativePath = relative,
                    selectedFileRelativePath = null
                )
            } else {
                RemoteFileLocation(
                    directoryRelativePath = parentPath(relative),
                    selectedFileRelativePath = relative
                )
            }
        }
    }

    fun preview(rootPath: String, file: RemoteFileEntry): RemoteFilePreview? {
        require(!file.isDirectory) { "Folders cannot be previewed as files." }
        val extension = file.name.substringAfterLast('.', "").lowercase()
        val image = extension in IMAGE_EXTENSIONS
        val maximum = if (image) MAX_IMAGE_PREVIEW_BYTES else MAX_TEXT_PREVIEW_BYTES
        if (file.sizeBytes > maximum) return null
        val bytes = readBoundedFile(rootPath, file.relativePath, maximum)
        if (image) {
            return RemoteFilePreview(
                file = file,
                kind = RemoteFilePreviewKind.IMAGE,
                bytes = bytes
            )
        }
        val text = decodeUtf8Text(bytes) ?: return null
        return RemoteFilePreview(
            file = file,
            kind = RemoteFilePreviewKind.TEXT,
            text = text
        )
    }

    internal fun readTextFile(
        rootPath: String,
        relativePath: String,
        maximumBytes: Int
    ): String? = decodeUtf8Text(readBoundedFile(rootPath, relativePath, maximumBytes.toLong()))

    private fun readBoundedFile(
        rootPath: String,
        relativePath: String,
        maximumBytes: Long
    ): ByteArray {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.isNotEmpty()) { "Choose a remote file." }
        return ssh.withSftp { sftp ->
            val root = canonicalRoot(sftp, rootPath)
            val remoteFile = resolveWithinRoot(sftp, root, normalized)
            val attributes = sftp.stat(remoteFile)
            require(!attributes.isDir && !attributes.isLink) {
                "Only regular project files can be opened."
            }
            require(attributes.size in 0..maximumBytes) {
                "This file is too large to preview. Download it instead."
            }
            val output = ByteArrayOutputStream(attributes.size.toInt().coerceAtLeast(32))
            sftp.get(remoteFile).use { input ->
                val buffer = ByteArray(16 * 1_024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size().toLong() + count <= maximumBytes) {
                        "This file is too large to preview. Download it instead."
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray()
        }
    }

    fun download(
        rootPath: String,
        relativePath: String,
        output: OutputStream,
        onProgress: (bytesCopied: Long, totalBytes: Long) -> Unit
    ) {
        val normalized = normalizeRelativePath(relativePath)
        require(normalized.isNotEmpty()) { "Choose a remote file to download." }
        ssh.withSftp { sftp ->
            val root = canonicalRoot(sftp, rootPath)
            val remoteFile = resolveWithinRoot(sftp, root, normalized)
            val attributes = sftp.stat(remoteFile)
            require(!attributes.isDir) { "Folders cannot be downloaded as a single file." }
            require(!attributes.isLink) { "Symbolic links cannot be downloaded." }

            val total = attributes.size.coerceAtLeast(0L)
            onProgress(0L, total)
            sftp.get(
                remoteFile,
                output,
                object : SftpProgressMonitor {
                    private var copied = 0L

                    override fun init(
                        op: Int,
                        src: String?,
                        dest: String?,
                        max: Long
                    ) = Unit

                    override fun count(count: Long): Boolean {
                        copied += count
                        onProgress(copied, total)
                        return true
                    }

                    override fun end() {
                        onProgress(total, total)
                    }
                }
            )
            output.flush()
        }
    }

    private fun canonicalRoot(sftp: ChannelSftp, rootPath: String): String {
        require(rootPath.startsWith('/')) { "The project folder is not an absolute remote path." }
        val root = sftp.realpath(rootPath).trimEnd('/').ifEmpty { "/" }
        require(sftp.stat(root).isDir) { "The project folder is not available." }
        return root
    }

    private fun resolveWithinRoot(
        sftp: ChannelSftp,
        canonicalRoot: String,
        relativePath: String
    ): String {
        val unresolved = if (relativePath.isEmpty()) {
            canonicalRoot
        } else if (canonicalRoot == "/") {
            "/$relativePath"
        } else {
            "$canonicalRoot/$relativePath"
        }
        val resolved = sftp.realpath(unresolved).trimEnd('/').ifEmpty { "/" }
        require(isWithinRoot(canonicalRoot, resolved)) {
            "The selected path leaves the project folder."
        }
        return resolved
    }

    private fun safeEntry(entry: ChannelSftp.LsEntry): Boolean {
        val name = entry.filename
        return !entry.attrs.isLink &&
            name.isNotBlank() &&
            name.length <= MAX_FILE_NAME_LENGTH &&
            '/' !in name &&
            '\u0000' !in name &&
            name.none { it.code in 0..31 || it.code == 127 }
    }

    companion object {
        private const val MAX_DIRECTORY_ENTRIES = 10_000
        private const val MAX_FILE_NAME_LENGTH = 255
        private const val MAX_TEXT_PREVIEW_BYTES = 1_048_576L
        private const val MAX_IMAGE_PREVIEW_BYTES = 12_582_912L
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "gif", "webp", "bmp")

        internal fun decodeUtf8Text(bytes: ByteArray): String? {
            if (bytes.any { it == 0.toByte() }) return null
            return runCatching {
                Charsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString()
            }.getOrNull()
        }

        internal fun normalizeRelativePath(path: String): String {
            if (path.isBlank()) return ""
            require(!path.startsWith('/')) { "Remote file paths must stay inside the project." }
            val segments = path.split('/')
            require(segments.all { segment ->
                segment.isNotBlank() &&
                    segment != "." &&
                    segment != ".." &&
                    '\u0000' !in segment &&
                    segment.none { it.code in 0..31 || it.code == 127 }
            }) {
                "The remote file path is invalid."
            }
            return segments.joinToString("/")
        }

        internal fun isWithinRoot(root: String, candidate: String): Boolean =
            candidate == root || root == "/" || candidate.startsWith("$root/")

        internal fun parentPath(path: String): String =
            normalizeRelativePath(path).substringBeforeLast('/', "")

        internal fun sanitizeReference(reference: String): String {
            val cleaned = reference
                .trim()
                .replace(Regex(":(\\d+)(?::\\d+)?$"), "")
                .trimEnd('/')
            require(
                cleaned.isNotBlank() &&
                    cleaned.length <= 4_096 &&
                    '\u0000' !in cleaned &&
                    cleaned.none { it.code in 0..31 || it.code == 127 }
            ) {
                "The terminal path is invalid."
            }
            return cleaned
        }

        internal fun relativeToRoot(root: String, candidate: String): String {
            require(isWithinRoot(root, candidate)) { "The path is outside the project folder." }
            return when {
                candidate == root -> ""
                root == "/" -> candidate.removePrefix("/")
                else -> candidate.removePrefix("$root/")
            }
        }

        private fun joinRelative(parent: String, name: String): String =
            if (parent.isEmpty()) name else "$parent/$name"
    }
}
