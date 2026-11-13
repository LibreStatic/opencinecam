/* SPDX-License-Identifier: Apache-2.0 */
/* Copyright 2026 OpenCineCam contributors */

package com.librestatic.opencinecam.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * PLAN-072 Delivery 2: the gallery's view of the recording drive. Takes live in `<tree>/OpenCineCam/<stem>/` and
 * stills in `<tree>/OpenCineCam/Stills/<stem>/`; a folder holding the hidden marker is still being written or
 * awaits recovery, the drive's IS_PENDING. Only the folder listing is read per page load; each take's files and
 * sidecars are read when the merge reaches it, and kept while its folder does not change.
 */

/** True for a document of the removable-storage provider, the only one a recording drive is adopted from. */
internal fun isDriveUri(uri: String): Boolean = uri.startsWith("content://$EXTERNAL_STORAGE_AUTHORITY/")

/**
 * Whether the take lives on the recording drive. Derived from its primary file rather than stored in
 * [LocalMediaTake], whose equality is the identity check behind sharing, renaming and proxy jobs.
 */
internal val LocalMediaTake.onDrive: Boolean get() = isDriveUri(primary.uri)

private val DriveDocumentUri = Regex("content://${Regex.escape(EXTERNAL_STORAGE_AUTHORITY)}/(?:tree/[^/?#]+/)?document/([^/?#]+)")

/** The document id of a removable-storage document URI (`FFF1-7571:Takes/OpenCineCam/A001/A001.mp4`), or null. */
internal fun externalStorageDocumentId(uri: String): String? {
    val encoded = DriveDocumentUri.matchEntire(uri)?.groupValues?.get(1) ?: return null
    // Uri.encode writes '+' as %2B, so a bare '+' is literal, as Uri.decode reads it.
    return runCatching { java.net.URLDecoder.decode(encoded.replace("+", "%2B"), "UTF-8") }.getOrNull()?.takeIf { it.isNotEmpty() }
}

/** The document id of [uri] when it names a visible file directly inside the folder [folderId]; null otherwise. */
internal fun driveMemberDocumentId(uri: String, folderId: String): String? {
    val id = onVolumeOf(externalStorageDocumentId(uri) ?: return null, folderId)
    if (!id.startsWith("$folderId/")) return null
    val name = id.substring(folderId.length + 1)
    return id.takeIf { SafDocuments.isPlainName(name) && !name.startsWith('.') }
}

/**
 * [id] as the volume of [reference] names it: a sidecar written while the drive was `FFF1-7571` still says so once vold
 * mounts it as `FFF1-7571-01` ([sameDrive]), and it names the same file. Unchanged for another drive or the same name.
 */
internal fun onVolumeOf(id: String, reference: String): String {
    val volume = id.substringBefore(':', "")
    val mounted = reference.substringBefore(':', "")
    if (volume.isEmpty() || mounted.isEmpty() || volume == mounted || !sameDrive(volume, mounted)) return id
    return mounted + id.substring(volume.length)
}

/** The take folder holding the document [uri]: its document id up to the last `/`, or null. */
internal fun driveFolderOf(uri: String): String? =
    externalStorageDocumentId(uri)?.takeIf { '/' in it }?.substringBeforeLast('/')

/** Characters [android.net.Uri.encode] leaves as they are; every other byte is `%XX`, upper-case. */
private const val SAF_UNRESERVED = "_-!.~'()*"

/** [android.net.Uri.encode] in plain Kotlin, so the transfer validators (no Android types) can rebuild a document URI. */
internal fun safUriEncode(value: String): String = buildString {
    for (byte in value.toByteArray(Charsets.UTF_8)) {
        val c = byte.toInt() and 0xff
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in SAF_UNRESERVED)) append(ch) else append('%').append("%02X".format(c))
    }
}

private fun safUriDecode(value: String): String? {
    val bytes = java.io.ByteArrayOutputStream()
    var index = 0
    while (index < value.length) {
        val ch = value[index]
        if (ch == '%') {
            if (index + 2 >= value.length) return null
            val hex = value.substring(index + 1, index + 3)
            if (!hex.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }) return null
            bytes.write(hex.toInt(16))
            index += 3
        } else {
            if (ch.code >= 0x80) return null
            bytes.write(ch.code)
            index++
        }
    }
    val decoder = Charsets.UTF_8.newDecoder()
    return runCatching { decoder.decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString() }.getOrNull()
}

private val CanonicalDriveDocument =
    Regex("content://${Regex.escape(EXTERNAL_STORAGE_AUTHORITY)}/tree/([^/?#]+)/document/([^/?#]+)")

/**
 * Whether [value] is a drive file in the one form the drive catalog and the recorder write it: a tree-based
 * document URI of the removable-storage provider, both ids encoded exactly as [android.net.Uri.encode] writes them
 * (so `%41` and `A` never alias one file), the document under its tree, no query, fragment, empty or dot segment.
 * This is what the persisted transfer and proxy records accept beside canonical MediaStore items (PLAN-072).
 */
internal fun canonicalDriveDocumentUri(value: String): Boolean {
    if (value.length > 1024) return false
    val match = CanonicalDriveDocument.matchEntire(value) ?: return false
    val tree = safUriDecode(match.groupValues[1]) ?: return false
    val document = safUriDecode(match.groupValues[2]) ?: return false
    if (safUriEncode(tree) != match.groupValues[1] || safUriEncode(document) != match.groupValues[2]) return false
    if (':' !in tree || document.any { it.code < 32 || it.code == 127 || it == '\\' }) return false
    val prefix = if (tree.endsWith(':')) tree else "$tree/"
    if (!document.startsWith(prefix)) return false
    val path = document.substringAfter(':')
    return path.isNotEmpty() && path.split('/').none { it.isEmpty() || it == "." || it == ".." }
}

/** The kind of a drive file from its extension; null for no canonical drive document or no media. */
internal fun driveOriginalKind(uri: String): LocalMediaKind? {
    if (!canonicalDriveDocumentUri(uri)) return null
    val name = externalStorageDocumentId(uri)?.substringAfterLast('/') ?: return null
    return when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4" -> LocalMediaKind.VIDEO
        "wav", "flac" -> LocalMediaKind.AUDIO
        in DrivePhotoMimes.keys -> LocalMediaKind.PHOTO
        RAW_VIDEO_EXTENSION.removePrefix(".") -> LocalMediaKind.RAW_VIDEO
        else -> null
    }
}

/** A take original's kind: its MediaStore collection, or a drive file's extension. */
internal fun takeOriginalKind(uri: String): LocalMediaKind? = mediaOriginalIdentity(uri)?.first ?: driveOriginalKind(uri)

/**
 * The collection a take member belongs to: a MediaStore item's, or for a drive file its media kind, any other
 * file (sidecars, motion logs) being METADATA. A drive `.ocraw` has none, as RAW takes are never proxied or grouped.
 */
internal fun takeMemberCollection(uri: String): MediaDeleteCollection? {
    mediaDeleteIdentity(uri)?.let { return it.collection }
    if (!canonicalDriveDocumentUri(uri)) return null
    return when (val kind = driveOriginalKind(uri)) {
        null -> MediaDeleteCollection.METADATA
        LocalMediaKind.RAW_VIDEO -> null
        else -> MediaDeleteCollection.valueOf(kind.name)
    }
}

/**
 * Whether two references name the same take member. A drive file is compared by its document id, as
 * [catalogTake] binds a sidecar's reference, since the recorder may write it in another URI form or under
 * the drive's other volume name ([onVolumeOf]).
 */
internal fun sameTakeMemberUri(a: String, b: String): Boolean = a == b ||
    isDriveUri(a) && isDriveUri(b) && externalStorageDocumentId(a)?.let { idA ->
        externalStorageDocumentId(b)?.let { idB -> onVolumeOf(idA, idB) == idB }
    } == true

/**
 * Whether [takeId] may name a proxied take whose primary is [originalUri]: a legacy file, a declared recording, or
 * (PLAN-072) a drive take whose sidecars declare no bundle, keyed by its folder. Proxies of all three stay on the phone.
 */
internal fun proxyTakeIdBinds(takeId: String, originalUri: String): Boolean =
    takeId == "legacy:$originalUri" || takeId.startsWith("take:") && canonicalMediaId(takeId.removePrefix("take:")) ||
        canonicalDriveDocumentUri(originalUri) && driveFolderOf(originalUri)?.let { takeId == "drive:$it" } == true

/**
 * The namespace a drive take was read under, rebuilt from its id and files (all in one folder), and its members as
 * the drive catalog numbered them; null when they do not agree. Lets a share plan re-run [catalogTake] on the take.
 */
internal fun driveTakeRows(take: LocalMediaTake): Pair<MediaNamespace, List<CatalogRow>>? {
    val folder = driveFolderOf(take.primary.uri) ?: return null
    if (take.originals.any { !canonicalDriveDocumentUri(it.uri) || driveFolderOf(it.uri) != folder }) return null
    val prefix = take.id.substringBefore(':', "")
    val id = take.id.substringAfter(':', "")
    val namespace = when (prefix) {
        "drive" -> MediaNamespace("", take.kind != LocalMediaKind.PHOTO, folder).takeIf { id == folder }
        "take", "still" -> MediaNamespace(id, prefix == "take", folder).takeIf { canonicalMediaId(id) }
        else -> null
    } ?: return null
    val rows = take.originals.sortedBy { it.name }.mapIndexed { index, artifact ->
        CatalogRow(artifact, index + 1L, driveFileRole(artifact.name, namespace.recording).kind, namespace)
    }
    return namespace to rows
}

/** The configured recording drive is unplugged or does not answer: the operation waits for it, nothing is lost. */
internal class DriveNotConnectedException : IllegalStateException(MESSAGE) {
    companion object {
        /** Also the failure text a proxy job keeps, which its dialog shows as `media_drive_not_connected`. */
        const val MESSAGE = "USB drive not connected"
    }
}

/** Whether [this] or a cause of it is [DriveNotConnectedException]. */
internal fun Throwable.driveNotConnected(): Boolean = generateSequence(this) { it.cause }.take(16).any { it is DriveNotConnectedException }

/**
 * A readable path for a file on the drive: the drive's [label], then the document's path on its volume
 * (`USB drive/Takes/OpenCineCam/A001/A001.mp4`). Null when [uri] is no drive document.
 */
internal fun drivePath(label: String, uri: String): String? {
    val id = externalStorageDocumentId(uri) ?: return null
    val path = id.substringAfter(':', "").trim('/').takeIf { it.isNotEmpty() } ?: return null
    return "${label.trim().trimEnd('/')}/$path"
}

/**
 * Whether the folder [folderId] may be deleted as one take: a plain, visible folder directly in `<tree>/OpenCineCam/`
 * (never `Stills` itself) for a recording, or directly in `<tree>/OpenCineCam/Stills/` for stills. The tree root,
 * `OpenCineCam`, `Stills` and anything deeper or elsewhere are refused.
 */
internal fun driveTakeFolderDeletable(treeDocumentId: String, folderId: String, recording: Boolean): Boolean {
    val base = SafDocuments.childDocumentId(treeDocumentId, SafRecordingRows.BASE_FOLDER)
    val parent = if (recording) base else SafDocuments.childDocumentId(base, SafStillRows.STILLS_FOLDER)
    if (!folderId.startsWith("$parent/")) return false
    val name = folderId.substring(parent.length + 1)
    return SafDocuments.isPlainName(name) && !name.startsWith('.') && !(recording && name == SafStillRows.STILLS_FOLDER)
}

/**
 * Where a drive take sorts: its folder's last change in milliseconds (the marker's deletion at commit is the last),
 * then its folder's document id.
 */
internal data class DrivePosition(val modifiedMillis: Long, val folderId: String) {
    /** Whether this take comes before a MediaStore candidate modified at [seconds]; at an equal second the phone's goes first. */
    fun ahead(seconds: Long, newestFirst: Boolean): Boolean {
        val own = Math.floorDiv(modifiedMillis, 1000L)
        return if (newestFirst) own > seconds else own < seconds
    }
}

internal fun drivePositionOrder(newestFirst: Boolean): Comparator<DrivePosition> =
    (if (newestFirst) compareByDescending<DrivePosition> { it.modifiedMillis } else compareBy { it.modifiedMillis })
        .thenBy { it.folderId }

/** One take folder on the drive; [load] reads its files and sidecars, null when it is no take (yet). */
internal class DriveCatalogEntry(val position: DrivePosition, val load: () -> DriveCatalogTake?)

internal data class DriveCatalogTake(val take: LocalMediaTake, val encoding: LocalMediaEncoding? = null)

/** The entries after [position] in gallery order; all of them, sorted, when [position] is null. */
internal fun List<DriveCatalogEntry>.after(position: DrivePosition?, newestFirst: Boolean): List<DriveCatalogEntry> {
    val order = drivePositionOrder(newestFirst)
    return sortedWith(compareBy(order) { it.position }).filter { position == null || order.compare(it.position, position) > 0 }
}

/** What a file in a take folder is to the catalog, from its name as [SafRecordingRows] recovers it. */
internal enum class DriveFileRole(val kind: LocalMediaKind?) {
    VIDEO(LocalMediaKind.VIDEO), AUDIO(LocalMediaKind.AUDIO), PHOTO(LocalMediaKind.PHOTO),
    /** Sidecars, motion logs and anything unknown, as the phone's Download namespace holds them. */
    DOCUMENT(null),
    /**
     * An OpenCine RAW file in a recording folder. A folder whose only media it is lists as one RAW_VIDEO take, as
     * [rawVideoTake] lists the phone's; beside an MP4 or a WAV it is ignored, never grouped.
     */
    RAW(LocalMediaKind.RAW_VIDEO),
    /** Hidden files. */
    SKIPPED(null),
}

internal fun driveFileRole(name: String, recording: Boolean): DriveFileRole {
    if (name.startsWith('.')) return DriveFileRole.SKIPPED
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when {
        extension == RAW_VIDEO_EXTENSION.removePrefix(".") -> if (recording) DriveFileRole.RAW else DriveFileRole.SKIPPED
        recording && extension == "mp4" -> DriveFileRole.VIDEO
        recording && (extension == "wav" || extension == "flac") -> DriveFileRole.AUDIO
        !recording && extension in DrivePhotoMimes -> DriveFileRole.PHOTO
        else -> DriveFileRole.DOCUMENT
    }
}

private val DrivePhotoMimes = mapOf("jpg" to "image/jpeg", "jpeg" to "image/jpeg", "dng" to "image/x-adobe-dng",
    "heic" to "image/heic", "heif" to "image/heif")

/**
 * The MIME a drive file is listed with. The provider derives it from the name and may not know an extension; the
 * gallery needs `video/`, `audio/` and `image/` to open media, and `application/json` to read sidecars.
 */
internal fun driveMime(name: String, role: DriveFileRole, reported: String): String {
    val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
    return when (role) {
        DriveFileRole.VIDEO -> reported.takeIf { it.startsWith("video/") } ?: "video/mp4"
        DriveFileRole.AUDIO -> reported.takeIf { it.startsWith("audio/") } ?: if (extension == "flac") "audio/flac" else "audio/x-wav"
        DriveFileRole.PHOTO -> reported.takeIf { it.startsWith("image/") } ?: DrivePhotoMimes.getValue(extension)
        DriveFileRole.RAW -> RawVideoOutput.MIME_TYPE
        else -> if (extension == "json") "application/json" else reported
    }
}

/**
 * The one canonical `bundleId` the take's readable sidecars agree on, or null when none declares one (or they
 * disagree, which the catalog then reports as invalid metadata).
 */
internal fun driveBundleId(documents: List<MetadataDocument>): String? {
    val ids = documents.mapNotNullTo(LinkedHashSet()) { document ->
        val text = document.text ?: return@mapNotNullTo null
        runCatching {
            if (!jsonNestingWithinBound(text)) return@runCatching null
            ((Json.parseToJsonElement(text) as? JsonObject)?.get("bundleId") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }.getOrNull()?.takeIf { runCatching { UUID.fromString(it).toString() == it }.getOrDefault(false) }
    }
    return ids.singleOrNull()
}

/** As the phone's namespace queries: media members, and sidecars plus attachments together. */
internal const val DRIVE_MEMBER_BOUND = 16
internal const val DRIVE_DOCUMENT_BOUND = 4

/**
 * The take in the folder [folderId] from its [files] (one listing), or null while the marker says it is in progress
 * or when it holds no media. The namespace is the sidecars' `bundleId`, else the folder; [read] reads a sidecar.
 * A folder whose only media is an `.ocraw` is a RAW take keyed `raw:<uri>` like the phone's (PLAN-071 §7).
 */
internal fun driveCatalogTake(folderId: String, recording: Boolean, files: List<SafDocument>,
    read: (LocalMediaArtifact) -> MetadataDocument, captureDocuments: (List<MetadataDocument>) -> Unit = {}): DriveCatalogTake? {
    if (files.any { it.name == SafRecordingRows.MARKER }) return null
    val listed = files.filter { it.mime != DocumentsContract.Document.MIME_TYPE_DIR }
        .map { it to driveFileRole(it.name, recording) }.filter { it.second != DriveFileRole.SKIPPED }.sortedBy { it.first.name }
        .map { (doc, role) -> role to LocalMediaArtifact(doc.uri.toString(), doc.name, driveMime(doc.name, role, doc.mime), doc.size,
            Math.floorDiv(doc.modifiedMillis, 1000L)) }
    val media = listed.filter { it.first.kind != null && it.first != DriveFileRole.RAW }
    if (media.isEmpty()) return listed.firstOrNull { it.first == DriveFileRole.RAW }?.let { DriveCatalogTake(rawVideoTake(it.second)) }
    val others = listed.filter { it.first.kind == null }.map { it.second }
    val incomplete = media.size > DRIVE_MEMBER_BOUND || others.size > DRIVE_DOCUMENT_BOUND
    val bounded = others.take(DRIVE_DOCUMENT_BOUND)
    val attachments = if (recording) bounded.filter(::isTakeAttachment) else emptyList()
    val documents = bounded.filter { it !in attachments }.map { if (it.mimeType == "application/json") read(it) else MetadataDocument(it, null) }
    captureDocuments(documents)
    val namespace = MediaNamespace(driveBundleId(documents) ?: "", recording, folderId)
    val rows = media.take(DRIVE_MEMBER_BOUND).mapIndexed { index, (role, artifact) -> CatalogRow(artifact, index + 1L, role.kind, namespace) }
    var encoding: LocalMediaEncoding? = null
    val take = catalogTake(namespace, rows, documents, incomplete, attachments) { encoding = it } ?: return null
    return DriveCatalogTake(take, encoding)
}

/** A take folder as the drive listed it. */
internal data class DriveFolder(val uri: Uri, val documentId: String, val recording: Boolean, val position: DrivePosition)

/**
 * The listing of one drive and the takes read from it, process-wide so a deletion anywhere can drop it. [stamp]
 * names the drive (tree and volume): another drive or folder starts afresh. A take is kept under its folder's id
 * and last change, so a refresh re-reads only folders that changed; nothing is kept for a folder that gave no take.
 */
internal class DriveCatalogCache(private val takeCapacity: Int = 512) {
    private var stamp: String? = null
    private var folders: List<DriveFolder>? = null
    /** Counts [forget] and [invalidate]: a take or a listing whose read started before one of them is not kept. */
    private var generation = 0L
    /** Counts the listings started: of two running at once, only the one started last is kept. */
    private var listings = 0L
    /** Counts the take loads started, and the latest started for each take key: of two at once, the one started last is kept. */
    private var loads = 0L
    private val latestLoads = HashMap<String, Long>()
    private val takes = object : LinkedHashMap<String, DriveCatalogTake>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, DriveCatalogTake>?): Boolean = size > takeCapacity
    }

    /** The cached listing of [stamp] unless [refresh]; a listing that failed ([list] null) is not kept. */
    fun folders(stamp: String, refresh: Boolean, list: () -> List<DriveFolder>?): List<DriveFolder> {
        val (started, listing) = synchronized(this) {
            if (stamp != this.stamp) { this.stamp = stamp; folders = null; takes.clear(); generation++ }
            if (!refresh) folders?.let { return it }
            generation to ++listings
        }
        val listed = list()
        // A listing that started before an invalidation, or before another listing, may be older than the one kept since.
        synchronized(this) { if (stamp == this.stamp && started == generation && listing == listings) folders = listed }
        return listed.orEmpty()
    }

    fun take(stamp: String, folder: DriveFolder, load: () -> DriveCatalogTake?): DriveCatalogTake? {
        val key = "${folder.documentId}|${folder.position.modifiedMillis}"
        val (started, loading) = synchronized(this) {
            if (stamp == this.stamp) takes[key]?.let { return it }
            generation to (++loads).also { latestLoads[key] = it }
        }
        val found = runCatching(load)
        synchronized(this) {
            val latest = latestLoads[key] == loading
            if (latest) latestLoads.remove(key)
            // An older load may have read the folder before a member was rewritten in place, and finished after a newer one.
            found.getOrNull()?.let { if (latest && stamp == this.stamp && started == generation) takes[key] = it }
        }
        return found.getOrThrow()
    }

    @Synchronized fun invalidate() { stamp = null; folders = null; takes.clear(); generation++ }

    /**
     * Drops the takes kept for folder [documentId]: a file rewritten in place (from a computer) leaves the folder's last
     * change as it was, so its cached take would never be read again.
     */
    @Synchronized fun forget(documentId: String) { takes.keys.removeAll { it.startsWith("$documentId|") }; generation++ }

    companion object {
        val Shared = DriveCatalogCache()
    }
}

/**
 * The gallery's reader of the configured recording drive. It contributes takes only while a drive is configured,
 * its tree grant is held ([readable]) and its folder answers; an unplugged drive contributes nothing, never an
 * empty library. A drive failure is logged and leaves the phone's takes listed: it never fails the gallery.
 * Every call blocks on the provider.
 */
internal class DriveMediaCatalog(
    private val resolver: ContentResolver,
    private val destination: () -> RecordingDestination,
    private val readable: (Uri) -> Boolean,
    private val read: (LocalMediaArtifact) -> MetadataDocument,
    private val cache: DriveCatalogCache = DriveCatalogCache.Shared,
) {
    /** The drive's take folders, unsorted. [refresh] lists the drive again; otherwise the same drive's listing is reused. */
    fun entries(refresh: Boolean): List<DriveCatalogEntry> {
        val tree = drive() ?: return emptyList()
        val stamp = stampOf(tree)
        return cache.folders(stamp, refresh) { listFolders(tree) }.map { folder ->
            DriveCatalogEntry(folder.position) { cache.take(stamp, folder) { load(tree, folder) } }
        }
    }

    /** Drops what is cached, after the drive changed under the gallery (a deletion). */
    fun invalidate() = cache.invalidate()

    /** The configured drive tree when its grant is held, else null. */
    fun drive(): Uri? {
        val drive = destination() as? RecordingDestination.UsbFolder ?: return null
        return drive.treeUri.takeIf(readable)
    }

    /**
     * The take [selected] as its folder holds it now, with the folder and its listing, for a deletion: the folder
     * must be a take folder of the configured drive ([driveTakeFolderDeletable]), not in progress, hold exactly the
     * selected files besides hidden ones and no subfolder, and read back as the same take.
     */
    fun deletionSnapshot(selected: LocalMediaTake): DriveDeletion {
        val tree = checkNotNull(drive()) { "The USB drive is not the recording destination or its access was lost" }
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val recording = selected.kind != LocalMediaKind.PHOTO
        val folderId = checkNotNull(driveFolderOf(selected.primary.uri)) { "Untrusted drive take URI" }
        check(driveTakeFolderDeletable(treeId, folderId, recording)) { "Not a take folder of the recording drive" }
        val confirmed = selected.originals + selected.metadata + selected.attachments
        val ids = confirmed.map { checkNotNull(driveMemberDocumentId(it.uri, folderId)) { "Deletion member outside the take folder" } }
        check(ids.distinct().size == ids.size) { "Duplicate confirmed deletion member" }
        val docs = SafDocuments(resolver, tree)
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
        check(docs.describe(folder)?.mime == DocumentsContract.Document.MIME_TYPE_DIR) { "The take folder is not on the drive" }
        val children = docs.children(folder)
        check(children.none { it.name == SafRecordingRows.MARKER }) { "Deletion take is not committed" }
        check(children.none { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }) { "The take folder holds a folder" }
        val visible = children.filterNot { it.name.startsWith('.') }.map { DocumentsContract.getDocumentId(it.uri) }
        check(visible.toSet() == ids.toSet() && visible.size == ids.size) { "Deletion selection changed; refresh gallery" }
        val fresh = driveCatalogTake(folderId, recording, children, read)?.take
        if (fresh != selected) cache.forget(folderId)
        check(fresh == selected) { "Deletion selection changed; refresh gallery" }
        return DriveDeletion(docs, folder, confirmed, ids.map { DocumentsContract.buildDocumentUriUsingTree(tree, it) })
    }

    /**
     * The take [selected] as its folder holds it now, with the sidecars read for it, for sharing and proxies: every
     * file still listed with the same size and time, none added, the folder not in progress. An unplugged drive, or
     * one whose grant is gone, is [DriveNotConnectedException]; a changed take fails as the phone's does.
     */
    fun shareSnapshot(selected: LocalMediaTake): MediaShareSnapshot {
        val tree = drive() ?: throw DriveNotConnectedException()
        val docs = SafDocuments(resolver, tree)
        val recording = selected.kind != LocalMediaKind.PHOTO
        val folderId = checkNotNull(driveFolderOf(selected.primary.uri)) { "Untrusted selected media URI" }
        check(driveTakeFolderDeletable(DocumentsContract.getTreeDocumentId(tree), folderId, recording)) { "Selected media is not on the recording drive" }
        val children = try {
            val folder = DocumentsContract.buildDocumentUriUsingTree(tree, folderId)
            docs.describe(folder)?.takeIf { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }?.let { docs.children(folder) }
        } catch (failure: Exception) {
            if (failure is CancellationException || failure is SecurityException) throw failure
            if (docs.describe(docs.root) == null) throw DriveNotConnectedException()
            throw failure
        }
        if (children == null) {
            if (runCatching { docs.describe(docs.root) }.getOrNull() == null) throw DriveNotConnectedException()
            error("Selected media changed; refresh gallery")
        }
        check(children.none { it.name == SafRecordingRows.MARKER }) { "Selected take is not committed" }
        var documents = emptyList<MetadataDocument>()
        val fresh = driveCatalogTake(folderId, recording, children, read) { documents = it }?.take
        // The next refresh reads the folder again instead of offering the stale take.
        if (fresh != selected) cache.forget(folderId)
        check(fresh != null) { "Selected media changed; refresh gallery" }
        requireUnchangedShareTake(selected, fresh)
        return MediaShareSnapshot(fresh, if (fresh.kind == LocalMediaKind.RAW_VIDEO) emptyList() else documents)
    }

    private fun stampOf(tree: Uri): String = "$tree|${(destination() as? RecordingDestination.UsbFolder)?.volumeId}"

    private fun listFolders(tree: Uri): List<DriveFolder>? = try {
        val docs = SafDocuments(resolver, tree)
        if (docs.describe(docs.root) == null) null
        else docs.child(docs.root, SafRecordingRows.BASE_FOLDER)?.takeIf { it.mime == DocumentsContract.Document.MIME_TYPE_DIR }?.let { base ->
            buildList {
                for (child in docs.children(base.uri)) {
                    if (child.mime != DocumentsContract.Document.MIME_TYPE_DIR || child.name.startsWith('.')) continue
                    if (child.name == SafStillRows.STILLS_FOLDER) {
                        docs.children(child.uri).filter { it.mime == DocumentsContract.Document.MIME_TYPE_DIR && !it.name.startsWith('.') }
                            .forEach { add(folderOf(it, recording = false)) }
                    } else add(folderOf(child, recording = true))
                }
            }
        } ?: emptyList()
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Log.w(LOG_TAG, "The recording drive could not be listed", failure)
        null
    }

    private fun folderOf(doc: SafDocument, recording: Boolean): DriveFolder {
        val id = DocumentsContract.getDocumentId(doc.uri)
        return DriveFolder(doc.uri, id, recording, DrivePosition(doc.modifiedMillis, id))
    }

    private fun load(tree: Uri, folder: DriveFolder): DriveCatalogTake? = try {
        driveCatalogTake(folder.documentId, folder.recording, SafDocuments(resolver, tree).children(folder.uri), read)
    } catch (failure: Exception) {
        if (failure is CancellationException) throw failure
        Log.w(LOG_TAG, "A take folder on the recording drive could not be read", failure)
        null
    }

    private companion object {
        const val LOG_TAG = "DriveMediaCatalog"
    }
}

/**
 * A drive take checked for deletion: each of [confirmed] goes on its own ([members], the same documents under the
 * drive's tree), then [folder] if nothing but a computer's own litter ([driveFolderLitter]) is left in it.
 */
internal class DriveDeletion(val docs: SafDocuments, val folder: Uri, val confirmed: List<LocalMediaArtifact>, val members: List<Uri>)

/** What macOS leaves in a folder it browsed (`.DS_Store`, `._<name>` AppleDouble files), which goes with the take's folder. */
internal fun driveFolderLitter(name: String): Boolean = name == ".DS_Store" || name.startsWith("._")
