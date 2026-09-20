/* SPDX-License-Identifier: Apache-2.0 */
package com.librestatic.opencinecam.transfers

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseCorruptException
import android.database.sqlite.SQLiteException
import android.database.sqlite.SQLiteOpenHelper

/**
 * Private, dependency-free min-29 persistence. SQLite transactions fence the entire bundle.
 * A corrupt/newer database is preserved for recovery, not deleted or reopened as an empty outbox.
 * No clock lease expiry is inferred: process recovery requires an explicit retired process token.
 */
class WebDavSqliteOutbox(context: Context, databaseName: String = "webdav-outbox.db") : WebDavOutboxStore {
    init { require(databaseName.matches(Regex("[A-Za-z0-9_-]+\\.db"))) }
    private val helper = Helper(context.applicationContext, databaseName)

    /** Local rename never retargets an immutable remote copy. Hold the affected unsent/current
     * snapshot before local mutation; verified/conflicting remote evidence remains history.
     * The caller holds the runtime idle-media reservation across this transaction and its IO. */
    internal fun holdSourcesForLocalRename(sourceUris: Set<String>): List<WebDavOutboxBundle> {
        require(sourceUris.isNotEmpty() && sourceUris.size <= 36)
        return transaction { db ->
            val count = db.rawQuery("SELECT count(*) FROM bundles", null).use { cursor ->
                check(cursor.moveToFirst()); integer(cursor, 0)
            }
            check(count in 0L..1024L) { "Outbox enumeration exceeds its durable bound" }
            val bundleIds = ids(db, 1024)
            check(bundleIds.size.toLong() == count) { "Outbox enumeration was incomplete" }
            val affected = bundleIds.map { requireNotNull(read(db, it)) }
                .filter { bundle -> bundle.artifacts.any { it.spec.sourceUri in sourceUris } }
            // Validate every matching bundle before changing even the first one. An unknown
            // recorded attempt is never stolen merely because the current runtime is idle.
            check(affected.all { it.sealed && it.artifacts.all { artifact -> artifact.attempt == null } }) {
                "Local rename requires finalized, retired transfer snapshots"
            }
            affected.map { before ->
                var current = before
                for (artifact in before.artifacts.filter { it.spec.sourceUri in sourceUris }) {
                    if (artifact.state in setOf(WebDavArtifactState.QUEUED, WebDavArtifactState.UNCERTAIN,
                            WebDavArtifactState.SOURCE_UNAVAILABLE)) {
                        val failure = WebDavSourceFailure(artifact.spec.sourceUri, WebDavSourceFailureReason.LOCAL_RENAME_REQUESTED)
                        if (artifact.sourceFailure != failure) {
                            current = requireNotNull(WebDavOutboxTransitions.sourceUnavailable(current,
                                artifact.spec.id, artifact.revision, failure))
                        }
                    }
                }
                if (current != before) persist(db, before, current) else before
            }
        }
    }

    override fun stage(plan: WebDavBundlePlan): WebDavOutboxBundle = transaction { db ->
        val frozen = plan.copy(artifacts = plan.artifacts.toList())
        val existing = read(db, frozen.id)
        if (existing != null) {
            require(existing.endpointId == frozen.endpointId && existing.endpointRevision == frozen.endpointRevision &&
                existing.artifacts.map { it.spec }.toSet() == frozen.artifacts.toSet()) { "Bundle identity is already bound" }
            existing
        } else {
            val count = db.rawQuery("SELECT count(*) FROM bundles", null).use { cursor -> cursor.moveToFirst(); integer(cursor, 0) }
            check(count < 1024) { "WebDAV outbox is full" }
            val bundle = WebDavOutboxTransitions.stage(frozen)
            db.insertOrThrow("bundles", null, bundleValues(bundle))
            for (artifact in bundle.artifacts) db.insertOrThrow("artifacts", null, artifactValues(bundle.id, artifact))
            requireNotNull(read(db, bundle.id))
        }
    }

    override fun load(bundleId: String): WebDavOutboxBundle? {
        requireOutboxUuid(bundleId)
        return transaction { read(it, bundleId) }
    }

    override fun list(limit: Int): List<WebDavOutboxBundle> {
        require(limit in 1..1024)
        return transaction { db -> ids(db, limit).map { requireNotNull(read(db, it)) } }
    }

    override fun seal(bundleId: String, expectedRevision: Long, publications: List<WebDavArtifactPublication>): WebDavOutboxBundle? {
        requireOutboxUuid(bundleId); require(expectedRevision >= 0)
        return transaction { db ->
            val current = read(db, bundleId) ?: return@transaction null
            if (current.revision != expectedRevision || current.sealed) return@transaction null
            persist(db, current, WebDavOutboxTransitions.seal(current, publications.toList()))
        }
    }

    override fun recordHash(bundleId: String, expectedArtifactRevision: Long, publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? {
        requireOutboxUuid(bundleId); require(expectedArtifactRevision >= 0); requireOutboxHash(sha256)
        return transaction { db ->
            val current = read(db, bundleId) ?: return@transaction null
            if (current.artifacts.none { it.spec.id == publication.artifactId && it.revision == expectedArtifactRevision }) return@transaction null
            persist(db, current, WebDavOutboxTransitions.hash(current, publication, sha256))
        }
    }

    override fun claim(bundleId: String, artifactId: String, expectedArtifactRevision: Long, attemptId: String,
        kind: WebDavAttemptKind, admission: WebDavOutboxAdmission): WebDavOutboxLease? {
        requireOutboxUuid(bundleId); requireOutboxUuid(artifactId); requireOutboxUuid(attemptId); require(expectedArtifactRevision >= 0)
        return transaction { db ->
            val current = read(db, bundleId) ?: return@transaction null
            val artifact = current.artifacts.singleOrNull { it.spec.id == artifactId } ?: return@transaction null
            if (artifact.revision != expectedArtifactRevision || artifact.attempt != null) return@transaction null
            val (updated, lease) = WebDavOutboxTransitions.claim(current, artifactId, attemptId, kind, admission)
            persist(db, current, updated)
            lease
        }
    }

    override fun finishPut(lease: WebDavOutboxLease, outcome: WebDavPutOutcome): Boolean = transaction { db ->
        val current = read(db, lease.bundleId) ?: return@transaction false
        val updated = WebDavOutboxTransitions.finishPut(current, lease, outcome) ?: return@transaction false
        persist(db, current, updated)
        true
    }

    override fun finishReconcile(lease: WebDavOutboxLease): Boolean = transaction { db ->
        val current = read(db, lease.bundleId) ?: return@transaction false
        val updated = WebDavOutboxTransitions.finishReconcile(current, lease) ?: return@transaction false
        persist(db, current, updated)
        true
    }

    override fun reconcile(lease: WebDavOutboxLease, evidence: WebDavReconciliationEvidence): Boolean = transaction { db ->
        val current = read(db, lease.bundleId) ?: return@transaction false
        val updated = WebDavOutboxTransitions.reconcile(current, lease, evidence) ?: return@transaction false
        persist(db, current, updated)
        true
    }

    override fun sourceUnavailable(bundleId: String, artifactId: String, expectedArtifactRevision: Long,
        failure: WebDavSourceFailure): Boolean {
        requireOutboxUuid(bundleId); requireOutboxUuid(artifactId); require(expectedArtifactRevision >= 0)
        return transaction { db ->
            val current = read(db, bundleId) ?: return@transaction false
            val updated = WebDavOutboxTransitions.sourceUnavailable(current, artifactId, expectedArtifactRevision, failure) ?: return@transaction false
            persist(db, current, updated)
            true
        }
    }

    override fun sourceUnavailable(lease: WebDavOutboxLease, failure: WebDavSourceFailure): Boolean = transaction { db ->
        val current = read(db, lease.bundleId) ?: return@transaction false
        val updated = WebDavOutboxTransitions.sourceUnavailable(current, lease, failure) ?: return@transaction false
        persist(db, current, updated)
        true
    }

    override fun sourceRecovered(bundleId: String, expectedArtifactRevision: Long,
        publication: WebDavArtifactPublication, sha256: String): WebDavOutboxBundle? {
        requireOutboxUuid(bundleId); require(expectedArtifactRevision >= 0); requireOutboxHash(sha256)
        return transaction { db ->
            val current = read(db, bundleId) ?: return@transaction null
            val updated = WebDavOutboxTransitions.sourceRecovered(current, expectedArtifactRevision, publication, sha256) ?: return@transaction null
            persist(db, current, updated)
        }
    }

    override fun recoverProcess(deadProcessToken: String): Int {
        requireOutboxUuid(deadProcessToken)
        return transaction { db ->
            val affectedIds = db.rawQuery("SELECT DISTINCT bundle_id FROM artifacts WHERE process_token=? LIMIT 1025", arrayOf(deadProcessToken))
                .use { cursor -> buildList { while (cursor.moveToNext()) add(string(cursor, 0)) } }
            if (affectedIds.size > 1024) throw WebDavOutboxCorruptData()
            var recovered = 0
            for (id in affectedIds) {
                val current = requireNotNull(read(db, id))
                recovered += current.artifacts.count { it.attempt?.processToken == deadProcessToken }
                persist(db, current, WebDavOutboxTransitions.recover(current, deadProcessToken))
            }
            recovered
        }
    }

    override fun close() { helper.close() }

    private fun <T> transaction(block: (SQLiteDatabase) -> T): T {
        val db = helper.writableDatabase
        db.beginTransaction()
        try {
            val result = block(db)
            db.setTransactionSuccessful()
            return result
        } finally {
            db.endTransaction()
        }
    }

    private fun persist(db: SQLiteDatabase, before: WebDavOutboxBundle, after: WebDavOutboxBundle): WebDavOutboxBundle {
        check(db.update("bundles", bundleValues(after), "id=? AND revision=?", arrayOf(before.id, before.revision.toString())) == 1)
        for (artifact in after.artifacts) {
            val previous = before.artifacts.single { it.spec.id == artifact.spec.id }
            check(db.update("artifacts", artifactValues(after.id, artifact), "id=? AND revision=?", arrayOf(artifact.spec.id, previous.revision.toString())) == 1)
        }
        val reopened = requireNotNull(read(db, after.id))
        check(reopened.copy(artifacts = reopened.artifacts.sortedBy { it.spec.id }) == after.copy(artifacts = after.artifacts.sortedBy { it.spec.id }))
        return reopened
    }

    private fun ids(db: SQLiteDatabase, limit: Int): List<String> = db.rawQuery("SELECT id FROM bundles ORDER BY id LIMIT ?", arrayOf(limit.toString()))
        .use { cursor -> buildList { while (cursor.moveToNext()) add(string(cursor, 0)) } }

    private fun read(db: SQLiteDatabase, id: String): WebDavOutboxBundle? = try {
        db.query("bundles", null, "id=?", arrayOf(id), null, null, null).use { bundle ->
            if (!bundle.moveToFirst()) return@use null
            val artifacts = db.query("artifacts", null, "bundle_id=?", arrayOf(id), null, null, "role").use { rows ->
                buildList {
                    while (rows.moveToNext()) {
                        if (size == 4) throw WebDavOutboxCorruptData()
                        add(readArtifact(rows))
                    }
                }
            }
            WebDavOutboxBundle(text(bundle, "id"), text(bundle, "endpoint_id"), long(bundle, "endpoint_revision"),
                long(bundle, "revision"), bool(bundle, "sealed"), artifacts)
        }
    } catch (_: IllegalArgumentException) {
        throw WebDavOutboxCorruptData()
    }

    private fun readArtifact(row: Cursor): WebDavOutboxArtifact {
        val attemptId = nullableText(row, "attempt_id")
        val kind = nullableText(row, "attempt_kind")
        val process = nullableText(row, "process_token")
        if (listOf(attemptId, kind, process).count { it != null } !in listOf(0, 3)) throw WebDavOutboxCorruptData()
        val admissionProcess = nullableText(row, "admission_process")
        val admissionRevision = nullableLong(row, "admission_revision")
        val network = nullableText(row, "admission_network")
        val cellular = nullableLong(row, "admission_cellular")
        if (listOf(admissionProcess, admissionRevision, network, cellular).count { it != null } !in listOf(0, 4)) throw WebDavOutboxCorruptData()
        if (cellular != null && cellular !in 0L..1L) throw WebDavOutboxCorruptData()
        val admission = admissionProcess?.let { WebDavOutboxAdmission(it, requireNotNull(admissionRevision),
            WebDavUploadPolicy(enabled = true, allowCellular = cellular == 1L, network = WebDavNetwork.valueOf(requireNotNull(network)))) }
        return WebDavOutboxArtifact(
            WebDavArtifactSpec(text(row, "id"), WebDavArtifactRole.valueOf(text(row, "role")), text(row, "source_uri"),
                text(row, "source_name"), long(row, "size_bytes"), text(row, "remote_name")),
            long(row, "revision"), nullableLong(row, "modified_seconds"), nullableText(row, "sha256"),
            WebDavArtifactState.valueOf(text(row, "state")),
            attemptId?.let { WebDavOutboxAttempt(it, WebDavAttemptKind.valueOf(requireNotNull(kind)), requireNotNull(process)) }, admission,
            bool(row, "remote_may_exist"),
            nullableText(row, "source_failure")?.let { WebDavSourceFailure(text(row, "source_uri"), WebDavSourceFailureReason.valueOf(it)) },
        )
    }

    private fun bundleValues(bundle: WebDavOutboxBundle) = ContentValues().apply {
        put("id", bundle.id); put("endpoint_id", bundle.endpointId); put("endpoint_revision", bundle.endpointRevision)
        put("revision", bundle.revision); put("sealed", if (bundle.sealed) 1 else 0)
    }

    private fun artifactValues(bundleId: String, artifact: WebDavOutboxArtifact) = ContentValues().apply {
        put("id", artifact.spec.id); put("bundle_id", bundleId); put("role", artifact.spec.role.name)
        put("source_uri", artifact.spec.sourceUri); put("source_name", artifact.spec.sourceName); put("remote_name", artifact.spec.remoteName)
        put("size_bytes", artifact.spec.sizeBytes); put("revision", artifact.revision); put("state", artifact.state.name)
        put("modified_seconds", artifact.modifiedSeconds); put("sha256", artifact.sha256)
        put("attempt_id", artifact.attempt?.id); put("attempt_kind", artifact.attempt?.kind?.name); put("process_token", artifact.attempt?.processToken)
        put("admission_process", artifact.lastAdmission?.processToken); put("admission_revision", artifact.lastAdmission?.policyRevision)
        put("admission_network", artifact.lastAdmission?.policy?.network?.name)
        put("admission_cellular", artifact.lastAdmission?.policy?.allowCellular?.let { if (it) 1 else 0 })
        put("remote_may_exist", if (artifact.remoteMayExist) 1 else 0)
        put("source_failure", artifact.sourceFailure?.reason?.name)
    }

    private class Helper(context: Context, name: String) : SQLiteOpenHelper(context, name, null, 1, DatabaseErrorHandler {
        throw SQLiteDatabaseCorruptException("WebDAV outbox is preserved for recovery")
    }) {
        override fun onConfigure(db: SQLiteDatabase) {
            db.setForeignKeyConstraintsEnabled(true)
            db.execSQL("PRAGMA synchronous=FULL")
        }
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""CREATE TABLE bundles (
                id TEXT PRIMARY KEY NOT NULL, endpoint_id TEXT NOT NULL,
                endpoint_revision INTEGER NOT NULL CHECK(endpoint_revision>=0),
                revision INTEGER NOT NULL CHECK(revision>=0), sealed INTEGER NOT NULL CHECK(sealed IN (0,1)))""")
            db.execSQL("""CREATE TABLE artifacts (
                id TEXT PRIMARY KEY NOT NULL, bundle_id TEXT NOT NULL REFERENCES bundles(id) ON DELETE CASCADE,
                role TEXT NOT NULL, source_uri TEXT NOT NULL, source_name TEXT NOT NULL, remote_name TEXT NOT NULL,
                size_bytes INTEGER NOT NULL CHECK(size_bytes>0), revision INTEGER NOT NULL CHECK(revision>=0),
                modified_seconds INTEGER CHECK(modified_seconds>=0), sha256 TEXT, state TEXT NOT NULL,
                attempt_id TEXT UNIQUE, attempt_kind TEXT, process_token TEXT,
                admission_process TEXT, admission_revision INTEGER CHECK(admission_revision>=0), admission_network TEXT,
                admission_cellular INTEGER CHECK(admission_cellular IN (0,1)),
                remote_may_exist INTEGER NOT NULL CHECK(remote_may_exist IN (0,1)), source_failure TEXT,
                UNIQUE(bundle_id,role), UNIQUE(bundle_id,source_uri), UNIQUE(bundle_id,remote_name))""")
        }
        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw SQLiteException("Unsupported WebDAV outbox schema") }
        override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { throw SQLiteException("Unsupported WebDAV outbox schema") }
    }

    companion object {
        private fun integer(cursor: Cursor, index: Int): Long {
            if (cursor.getType(index) != Cursor.FIELD_TYPE_INTEGER) throw WebDavOutboxCorruptData()
            return cursor.getLong(index)
        }
        private fun string(cursor: Cursor, index: Int): String {
            if (cursor.getType(index) != Cursor.FIELD_TYPE_STRING) throw WebDavOutboxCorruptData()
            return cursor.getString(index)
        }
        private fun text(cursor: Cursor, name: String): String = string(cursor, cursor.getColumnIndexOrThrow(name))
        private fun long(cursor: Cursor, name: String): Long = integer(cursor, cursor.getColumnIndexOrThrow(name))
        private fun nullableText(cursor: Cursor, name: String): String? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else string(cursor, it) }
        private fun nullableLong(cursor: Cursor, name: String): Long? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else integer(cursor, it) }
        private fun bool(cursor: Cursor, name: String): Boolean = long(cursor, name).also { if (it !in 0L..1L) throw WebDavOutboxCorruptData() } == 1L
    }
}
