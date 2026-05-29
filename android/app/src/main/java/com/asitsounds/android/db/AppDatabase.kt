package com.asitsounds.android.db

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

private const val DB_NAME = "as-it-sounds.sqlite"
private const val DB_VERSION = 1

/**
 * Hand-rolled SQLite wrapper. We avoided Room because the current Kotlin 2.0.21
 * + KSP 2.0.21-1.0.27 + AGP 8.7 combination triggers "Wrong plugin option
 * format: null" during `:app:kspDebugKotlin`. Plain SQLiteOpenHelper is fine
 * for the demo schema (3 tables) and keeps the dependency surface tiny. If we
 * later need migrations, FTS, or @Transaction-style codegen we can swap in
 * Room without touching call sites — the DAO API below is intentionally
 * compatible with Room's contract.
 */
class AppDatabase private constructor(ctx: Context) :
    SQLiteOpenHelper(ctx.applicationContext, DB_NAME, null, DB_VERSION) {

    private val changeSignal = MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE projects (
              id TEXT PRIMARY KEY,
              name TEXT NOT NULL,
              createdAt INTEGER NOT NULL,
              updatedAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE media_files (
              id TEXT PRIMARY KEY,
              projectId TEXT NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
              path TEXT NOT NULL,
              displayName TEXT NOT NULL,
              mime TEXT,
              durationMs INTEGER,
              status TEXT NOT NULL,
              error TEXT,
              createdAt INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_media_project ON media_files(projectId)")
        db.execSQL(
            """
            CREATE TABLE segments (
              id TEXT PRIMARY KEY,
              fileId TEXT NOT NULL REFERENCES media_files(id) ON DELETE CASCADE,
              idx INTEGER NOT NULL,
              startMs INTEGER NOT NULL,
              endMs INTEGER NOT NULL,
              textEn TEXT NOT NULL,
              textHangul TEXT,
              textKo TEXT
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_segments_file ON segments(fileId, idx)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Phase 1B/2 ship with v1 only.
    }

    override fun onConfigure(db: SQLiteDatabase) {
        db.setForeignKeyConstraintsEnabled(true)
    }

    fun projectDao(): ProjectDao = ProjectDao(this, ::signal)
    fun mediaFileDao(): MediaFileDao = MediaFileDao(this, ::signal)
    fun segmentDao(): SegmentDao = SegmentDao(this, ::signal)

    internal fun signal() = changeSignal.tryEmit(Unit)
    internal fun changes(): Flow<Unit> = changeSignal

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: AppDatabase(context.applicationContext).also {
                instance = it
                // Seed the replay slot so collectors fire once on subscribe.
                it.signal()
            }
        }
    }
}

class ProjectDao(private val db: AppDatabase, private val signal: () -> Unit) {
    suspend fun insert(p: ProjectEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict("projects", null, p.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE)
        signal()
    }
    suspend fun delete(p: ProjectEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("projects", "id = ?", arrayOf(p.id)); signal()
    }
    suspend fun get(id: String): ProjectEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("projects", null, "id = ?", arrayOf(id), null, null, null, "1")
            .use { if (it.moveToFirst()) it.toProject() else null }
    }
    fun observeAll(): Flow<List<ProjectEntity>> = db.changes().map { query() }.onStart { emit(query()) }.flowOn(Dispatchers.IO)
    private fun query(): List<ProjectEntity> {
        return db.readableDatabase.query("projects", null, null, null, null, null, "updatedAt DESC").use { c ->
            ArrayList<ProjectEntity>(c.count).also { while (c.moveToNext()) it += c.toProject() }
        }
    }
}

class MediaFileDao(private val db: AppDatabase, private val signal: () -> Unit) {
    suspend fun insert(f: MediaFileEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict("media_files", null, f.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE)
        signal()
    }
    suspend fun update(f: MediaFileEntity) = insert(f)
    suspend fun get(id: String): MediaFileEntity? = withContext(Dispatchers.IO) {
        db.readableDatabase.query("media_files", null, "id = ?", arrayOf(id), null, null, null, "1")
            .use { if (it.moveToFirst()) it.toMediaFile() else null }
    }
    fun observeByProject(projectId: String): Flow<List<MediaFileEntity>> =
        db.changes().map { listByProject(projectId) }.onStart { emit(listByProject(projectId)) }.flowOn(Dispatchers.IO)
    private fun listByProject(projectId: String): List<MediaFileEntity> {
        return db.readableDatabase.query("media_files", null, "projectId = ?", arrayOf(projectId),
            null, null, "createdAt ASC").use { c ->
            ArrayList<MediaFileEntity>(c.count).also { while (c.moveToNext()) it += c.toMediaFile() }
        }
    }
}

class SegmentDao(private val db: AppDatabase, private val signal: () -> Unit) {
    suspend fun clearForFile(fileId: String) = withContext(Dispatchers.IO) {
        db.writableDatabase.delete("segments", "fileId = ?", arrayOf(fileId)); signal()
    }
    suspend fun insertAll(rows: List<SegmentEntity>) = withContext(Dispatchers.IO) {
        val wdb = db.writableDatabase
        wdb.beginTransaction()
        try {
            for (s in rows) wdb.insertWithOnConflict("segments", null, s.toContentValues(),
                SQLiteDatabase.CONFLICT_REPLACE)
            wdb.setTransactionSuccessful()
        } finally { wdb.endTransaction(); signal() }
    }
    suspend fun listByFile(fileId: String): List<SegmentEntity> = withContext(Dispatchers.IO) {
        db.readableDatabase.query("segments", null, "fileId = ?", arrayOf(fileId), null, null, "idx ASC")
            .use { c -> ArrayList<SegmentEntity>(c.count).also { while (c.moveToNext()) it += c.toSegment() } }
    }
}

// ─── Row mappers ───────────────────────────────────────────────────────────

private fun ProjectEntity.toContentValues() = ContentValues().apply {
    put("id", id); put("name", name); put("createdAt", createdAt); put("updatedAt", updatedAt)
}
private fun Cursor.toProject() = ProjectEntity(
    id = getString(getColumnIndexOrThrow("id")),
    name = getString(getColumnIndexOrThrow("name")),
    createdAt = getLong(getColumnIndexOrThrow("createdAt")),
    updatedAt = getLong(getColumnIndexOrThrow("updatedAt")),
)
private fun MediaFileEntity.toContentValues() = ContentValues().apply {
    put("id", id); put("projectId", projectId); put("path", path); put("displayName", displayName)
    put("mime", mime); durationMs?.let { put("durationMs", it) } ?: putNull("durationMs")
    put("status", status.name); put("error", error); put("createdAt", createdAt)
}
private fun Cursor.toMediaFile() = MediaFileEntity(
    id = getString(getColumnIndexOrThrow("id")),
    projectId = getString(getColumnIndexOrThrow("projectId")),
    path = getString(getColumnIndexOrThrow("path")),
    displayName = getString(getColumnIndexOrThrow("displayName")),
    mime = getStringOrNull("mime"),
    durationMs = if (isNullAt("durationMs")) null else getLong(getColumnIndexOrThrow("durationMs")),
    status = FileStatus.valueOf(getString(getColumnIndexOrThrow("status"))),
    error = getStringOrNull("error"),
    createdAt = getLong(getColumnIndexOrThrow("createdAt")),
)
private fun SegmentEntity.toContentValues() = ContentValues().apply {
    put("id", id); put("fileId", fileId); put("idx", idx); put("startMs", startMs); put("endMs", endMs)
    put("textEn", textEn); put("textHangul", textHangul); put("textKo", textKo)
}
private fun Cursor.toSegment() = SegmentEntity(
    id = getString(getColumnIndexOrThrow("id")),
    fileId = getString(getColumnIndexOrThrow("fileId")),
    idx = getInt(getColumnIndexOrThrow("idx")),
    startMs = getLong(getColumnIndexOrThrow("startMs")),
    endMs = getLong(getColumnIndexOrThrow("endMs")),
    textEn = getString(getColumnIndexOrThrow("textEn")),
    textHangul = getStringOrNull("textHangul"),
    textKo = getStringOrNull("textKo"),
)

private fun Cursor.getStringOrNull(col: String): String? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getString(i)
}
private fun Cursor.isNullAt(col: String) = isNull(getColumnIndexOrThrow(col))
