package dev.hardline.media

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.StatFs
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import dev.hardline.core.Keys
import dev.hardline.core.Settings
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Where recordings and snapshots go: the shared media store, a user-chosen folder, or plain files. */
class Storage(private val context: Context, private val settings: Settings) {
    class Entry(val name: String, val uri: Uri, val size: Long, val modified: Long, val motion: Boolean, val video: Boolean, val file: File? = null)

    class Output(val name: String, val uri: Uri, val descriptor: ParcelFileDescriptor, private val onFinish: (keep: Boolean) -> Unit) {
        private var finished = false
        fun finish(keep: Boolean = true) {
            if (finished) return
            finished = true
            runCatching { descriptor.close() }
            onFinish(keep)
        }
    }

    private val resolver = context.contentResolver
    private val treeUri: Uri? get() = settings[Keys.saveTreeUri].takeIf { settings[Keys.saveToTree] && it.isNotEmpty() }?.let(Uri::parse)

    fun fileName(prefix: String, extension: String, deviceName: String?): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val device = deviceName?.takeIf { settings[Keys.deviceNameInFilename] }?.replace(Regex("[^A-Za-z0-9]+"), "-")?.trim('-')
        return listOfNotNull(prefix, stamp, device?.takeIf { it.isNotEmpty() }).joinToString("_") + "." + extension
    }

    fun create(name: String, mime: String, motion: Boolean): Output? = runCatching {
        val tree = treeUri
        when {
            tree != null -> {
                var dir = DocumentFile.fromTreeUri(context, tree) ?: return null
                if (motion) dir = dir.findFile(MOTION) ?: dir.createDirectory(MOTION) ?: return null
                val doc = dir.createFile(mime, name) ?: return null
                val pfd = resolver.openFileDescriptor(doc.uri, "rw") ?: return null
                Output(name, doc.uri, pfd) { keep -> if (!keep) doc.delete() }
            }
            Build.VERSION.SDK_INT >= 29 -> {
                val video = mime.startsWith("video")
                val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, if (motion) "$FOLDER/$MOTION" else FOLDER)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = resolver.insert(collection, values) ?: return null
                val pfd = resolver.openFileDescriptor(uri, "rw") ?: return null
                Output(name, uri, pfd) { keep ->
                    if (keep) resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                    else resolver.delete(uri, null, null)
                }
            }
            else -> {
                val dir = legacyDir(motion).apply { mkdirs() }
                val file = File(dir, name)
                val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE)
                Output(name, Uri.fromFile(file), pfd) { keep -> if (!keep) file.delete() }
            }
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun legacyDir(motion: Boolean): File {
        val base = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "HardLine")
        return if (motion) File(base, MOTION) else base
    }

    /** Everything this app has saved, newest first. */
    fun list(): List<Entry> = runCatching {
        val out = ArrayList<Entry>()
        val tree = treeUri
        when {
            tree != null -> {
                fun scan(dir: DocumentFile, motion: Boolean) {
                    for (f in dir.listFiles()) {
                        val n = f.name ?: continue
                        if (f.isDirectory) { if (n == MOTION) scan(f, true) } else if (n.endsWith(".mp4") || n.endsWith(".jpg")) {
                            out += Entry(n, f.uri, f.length(), f.lastModified(), motion, n.endsWith(".mp4"))
                        }
                    }
                }
                DocumentFile.fromTreeUri(context, tree)?.let { scan(it, false) }
            }
            Build.VERSION.SDK_INT >= 29 -> {
                for (video in listOf(true, false)) {
                    val collection = if (video) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    val cols = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
                        MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH)
                    resolver.query(collection, cols, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?", arrayOf("$FOLDER/%"), null)?.use { c ->
                        while (c.moveToNext()) {
                            out += Entry(c.getString(1), Uri.withAppendedPath(collection, c.getLong(0).toString()), c.getLong(2),
                                c.getLong(3) * 1000, c.getString(4).contains(MOTION), video)
                        }
                    }
                }
            }
            else -> for (motion in listOf(false, true)) {
                legacyDir(motion).listFiles()?.filter { it.isFile }?.forEach {
                    out += Entry(it.name, Uri.fromFile(it), it.length(), it.lastModified(), motion, it.name.endsWith(".mp4"), it)
                }
            }
        }
        out.sortedByDescending { it.modified }
    }.getOrDefault(emptyList())

    fun open(entry: Entry): ParcelFileDescriptor? = runCatching {
        entry.file?.let { ParcelFileDescriptor.open(it, ParcelFileDescriptor.MODE_READ_ONLY) } ?: resolver.openFileDescriptor(entry.uri, "r")
    }.getOrNull()

    fun delete(entry: Entry): Boolean = runCatching {
        when {
            entry.file != null -> entry.file.delete()
            treeUri != null -> DocumentFile.fromSingleUri(context, entry.uri)?.delete() == true
            else -> resolver.delete(entry.uri, null, null) > 0
        }
    }.getOrDefault(false)

    fun freeBytes(): Long = runCatching {
        StatFs(Environment.getExternalStorageDirectory().path).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    /**
     * Loop recording: deletes the oldest recordings of one kind until the kind's total is under
     * [limitBytes] (0 = no limit) and at least [reserveBytes] of storage are free.
     */
    fun trim(motion: Boolean, limitBytes: Long, reserveBytes: Long, protect: String?) {
        val files = list().filter { it.video && it.motion == motion && it.name != protect }.sortedBy { it.modified }.toMutableList()
        var total = files.sumOf { it.size }
        while (files.isNotEmpty() && ((limitBytes > 0 && total > limitBytes) || freeBytes() < reserveBytes)) {
            val oldest = files.removeAt(0)
            if (!delete(oldest)) break
            total -= oldest.size
        }
    }

    companion object {
        const val FOLDER = "DCIM/HardLine"
        const val MOTION = "Motion"
    }
}
