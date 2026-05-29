# `AppDatabase.kt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../../../../INDEX.md)

**파일 요약**
- 역할: SQLiteOpenHelper 기반 영구 저장소 + 3 DAO + Flow change signal.
- LOC: 214
- exports: `AppDatabase`, `ProjectDao`, `MediaFileDao`, `SegmentDao`
- 의존: `android.database.sqlite.*`, `kotlinx.coroutines.flow.*`, `db/Entities.kt`
- 부수효과: `as-it-sounds.sqlite` 디스크 파일 (3 테이블 + 2 인덱스 + FK cascade)
- 동시성: 모든 DAO 함수가 `withContext(Dispatchers.IO)` — main thread 안전

> 본 파일은 214 LOC 로 임계치(600)를 한참 밑돌지만, **DAO 3개 × CRUD 5개 = 15 메서드** 가 반복 패턴이라 핵심 블록만 분석한다.

---

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=19-33
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
```

**분석 (Level 2)**
- DocComment 가 Room 회피 사유 + DAO API 호환성 유지 의도 명문화 — KSP 호환성 incident 의 historical 기록. 유지보수성 ↑.
- `private constructor` + `companion object.get(...)` 패턴으로 사실상 singleton. multi-instance 방지.
- `ctx.applicationContext` — Activity context 누수 방지. SQLiteOpenHelper 가 long-lived 라 안전.

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=34-78
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
```

**분석 (Level 2)**
- `MutableSharedFlow<Unit>(replay = 1, extraBufferCapacity = 8)`:
  - `replay = 1` — 새 collector 가 마지막 신호 1회 즉시 받음 → `onStart { emit(query()) }` 패턴이 이 효과를 흉내내지만 replay 가 더 깨끗.
  - `extraBufferCapacity = 8` — write 가 burst 로 들어와도 drop 없이 buffer. caller (DAO 호출) 가 suspend 없이 emit.
- Schema 3 테이블:
  - `projects` 단순 메타
  - `media_files` projectId FK + status (`pending|processing|done|error` — Entities.kt 의 FileStatus enum)
  - `segments` fileId FK + (startMs, endMs, textEn, textHangul, textKo)
  - 2 인덱스 — projectId, fileId+idx
- `ON DELETE CASCADE` — 프로젝트 삭제 시 media_files + segments 자동 정리. iOS GRDB 의 `.cascade` 와 시맨틱 일치.
- INTEGER (epoch ms) 로 시간 저장 — Room 의 `@TypeConverter Date` 보다 단순. iOS Date 와 epoch 단위 약속만 맞으면 cross-platform 가능.

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=80-105
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
```

**분석 (Level 2)**
- `onUpgrade` 빈 함수 + 주석: v1 only 명시. v2 추가 시 schema migration 로직 필요.
- `setForeignKeyConstraintsEnabled(true)`: SQLite 의 FK 가 기본 OFF — 명시 활성. ON DELETE CASCADE 가 동작하려면 필수.
- DAO 3개 모두 `this`, `::signal` (method reference) 전달 — 변경 신호 통합 채널.
- `companion object.get(...)` Double-checked locking 패턴:
  - 첫 read `instance ?: ...` — happy path 빠른 return.
  - `synchronized(this)` 안에서 두 번째 check + create.
  - `@Volatile` 로 다른 스레드가 즉시 보임.
  - JVM 의 published reference safe — `instance = it` 가 `it.signal()` 이전에 발생 시 다른 스레드가 partially-init 객체 볼 가능성. 다행히 `it.signal()` 가 `tryEmit` 만 호출하고 부수효과 없음 — 안전.

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=107-126
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
```

**분석 (Level 2)**
- 모든 suspend fun 이 `withContext(Dispatchers.IO)` 로 IO 스레드 강제 — caller 가 main thread 면 자동 hop.
- `insertWithOnConflict(..., CONFLICT_REPLACE)`: upsert 시맨틱. PK 충돌 시 row 교체 — Room 의 `@OnConflict(REPLACE)` 동등.
- `delete(..., "id = ?", arrayOf(p.id))`: parameterized query — SQL injection 방어.
- `cursor.use { ... }`: `use` 가 Cursor 의 close 보장. leak 방지.
- `observeAll()`: `db.changes() → map → onStart` 패턴:
  - `db.changes()` SharedFlow → 매 write 시 query 재실행
  - `onStart { emit(query()) }` 첫 subscribe 시 즉시 1회 emit
  - `flowOn(IO)` 디스패처 hop
- replay=1 인 SharedFlow + onStart 둘 다 첫 emit 보장 — **잠재 중복**: replay 가 1회 + onStart 가 1회 → 2번 emit 가능. 동일 결과면 무관 (LazyColumn 의 distinctUntilChanged 같은 가드 없음).

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=128-166
class MediaFileDao(private val db: AppDatabase, private val signal: () -> Unit) {
    suspend fun insert(f: MediaFileEntity) = withContext(Dispatchers.IO) {
        db.writableDatabase.insertWithOnConflict("media_files", null, f.toContentValues(),
            SQLiteDatabase.CONFLICT_REPLACE)
        signal()
    }
    suspend fun update(f: MediaFileEntity) = insert(f)
    ...

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
```

**분석 (Level 2)**
- `update(f) = insert(f)`: REPLACE 충돌 정책 + same PK → effectively upsert. 명확성 떨어지나 코드 절약.
- `SegmentDao.insertAll` 은 **명시적 트랜잭션** (`beginTransaction` ... `setTransactionSuccessful` ... `endTransaction`):
  - 100 segment 짜리 insert 가 단일 commit → 디스크 fsync 1회. 트랜잭션 없으면 SQLite 가 매 insert 후 commit → 100× 느림.
  - `try/finally` 가 endTransaction 보장. setTransactionSuccessful 안 호출되면 rollback.
- `signal()` 이 finally 안에 — rollback 후에도 호출 → collector 가 query 재실행. 잘못된 신호. 권고: `setTransactionSuccessful` 직후만 호출.

```kotlin path=android/app/src/main/java/com/asitsounds/android/db/AppDatabase.kt lines=168-214
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
...

private fun Cursor.getStringOrNull(col: String): String? {
    val i = getColumnIndexOrThrow(col)
    return if (isNull(i)) null else getString(i)
}
private fun Cursor.isNullAt(col: String) = isNull(getColumnIndexOrThrow(col))
```

**분석 (Level 2)**
- 정적 row mapper 함수 — Room codegen 의 결과물과 동일 형태. boilerplate.
- `getColumnIndexOrThrow` 가 컬럼 부재 시 즉시 `IllegalArgumentException` — schema-row mapper drift 빠른 검출.
- `getStringOrNull` extension 으로 nullable column 처리. `mime`, `error`, `textHangul`, `textKo`, `durationMs` 5 컬럼이 nullable.
- `Cursor.getColumnIndexOrThrow` 가 column 마다 hash lookup 수행 — 한 row 만 읽을 때 무관, 큰 list (1000 rows × 9 col = 9000 lookup) 면 hot path. `getColumnIndex` 1 회 cache → 반복 회피하는 패턴 (Room codegen 동등) 가능.

**짚어볼 점 / Watch-outs**
- **`SegmentDao.insertAll` 의 `signal()` 이 finally 안** → rollback 후에도 emit, 잘못된 데이터 view 가능.
- column index lookup 매 row 마다 — 마이크로 최적화 여지.
- DAO API 가 Room `@Dao` 와 시그니처 호환 의도 명문화 — 향후 마이그레이션 가능.
- `setForeignKeyConstraintsEnabled` 가 `onConfigure` 에서 호출되는데, API 16+ 부터 default true 라 사실 redundant — 명시 OK.
- `replay=1` SharedFlow + `onStart` 이중 emit — 가벼운 중복.

**파일 수준 평가**
- 강점:
  - Room 미사용 회피 사유 명문화 → 미래 마이그레이션 시 합의 유지.
  - DAO 호환 API + Flow 통합 — Room 으로 swap 시 변경 점 최소.
  - 단일 트랜잭션 + signal pattern 견고.
- 위험:
  - `insertAll` 의 signal 위치 잘못.
  - column lookup 마이크로 최적화 부재.
- 개선 권고:
  1. `signal()` 호출을 `setTransactionSuccessful()` **직후** 로 이동:
     ```kotlin
     try {
         for (s in rows) wdb.insertWithOnConflict(...)
         wdb.setTransactionSuccessful()
         signal()  // here
     } finally { wdb.endTransaction() }
     ```
  2. 큰 list query 시 column index pre-cache.
  3. Phase 2 에서 Room 마이그레이션 (KSP 안정화 후).
- 재읽기 권고 레벨: 3 (Level 3 면 SQLite 트랜잭션 isolation + WAL mode 검토)

**Cross-references**
- 호출처: `whisper/Pipeline.kt:102-105`, `service/TranscribeForegroundService.kt:97-110`, `ui/LibraryScreen.kt`
- iOS counterpart: `ios/.../Core/Database.swift` — GRDB `DatabaseMigrator` v1 동일 schema. iOS 는 `dbQueue.write { db in try p.insert(db) }` GRDB 특유 패턴.
