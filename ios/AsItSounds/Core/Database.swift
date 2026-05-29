import Foundation
import GRDB

// ─── Records (1:1 with the desktop schema) ─────────────────────────────────

public struct Project: Codable, FetchableRecord, PersistableRecord, Identifiable, Sendable, Equatable {
    public var id: String
    public var name: String
    public var createdAt: Date
    public var updatedAt: Date

    public static let databaseTableName = "projects"
}

public struct MediaFile: Codable, FetchableRecord, PersistableRecord, Identifiable, Sendable, Equatable {
    public enum Status: String, Codable, Sendable { case pending, processing, done, error }

    public var id: String
    public var projectId: String
    public var path: String
    public var displayName: String
    public var mime: String?
    public var durationMs: Int64?
    public var status: Status
    public var error: String?
    public var createdAt: Date

    public static let databaseTableName = "media_files"
}

public struct SegmentRecord: Codable, FetchableRecord, PersistableRecord, Identifiable, Sendable, Equatable {
    public var id: String
    public var fileId: String
    public var idx: Int
    public var startMs: Int64
    public var endMs: Int64
    public var textEn: String
    public var textHangul: String?
    public var textKo: String?

    public static let databaseTableName = "segments"
}

// ─── Database wrapper ──────────────────────────────────────────────────────

public final class AppDatabase: @unchecked Sendable {
    public static let shared: AppDatabase = {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let url = dir.appendingPathComponent("as-it-sounds.sqlite")
        // Force-unwrap is intentional: app cannot meaningfully run without DB.
        return try! AppDatabase(path: url.path)
    }()

    public let dbQueue: DatabaseQueue

    public init(path: String) throws {
        var config = Configuration()
        config.foreignKeysEnabled = true
        dbQueue = try DatabaseQueue(path: path, configuration: config)
        try migrator.migrate(dbQueue)
    }

    private var migrator: DatabaseMigrator {
        var m = DatabaseMigrator()
        m.registerMigration("v1") { db in
            try db.create(table: "projects") { t in
                t.column("id",         .text).primaryKey()
                t.column("name",       .text).notNull()
                t.column("createdAt",  .datetime).notNull()
                t.column("updatedAt",  .datetime).notNull()
            }
            try db.create(table: "media_files") { t in
                t.column("id",          .text).primaryKey()
                t.column("projectId",   .text).notNull().references("projects", onDelete: .cascade)
                t.column("path",        .text).notNull()
                t.column("displayName", .text).notNull()
                t.column("mime",        .text)
                t.column("durationMs",  .integer)
                t.column("status",      .text).notNull()
                t.column("error",       .text)
                t.column("createdAt",   .datetime).notNull()
            }
            try db.create(table: "segments") { t in
                t.column("id",         .text).primaryKey()
                t.column("fileId",     .text).notNull().references("media_files", onDelete: .cascade)
                t.column("idx",        .integer).notNull()
                t.column("startMs",    .integer).notNull()
                t.column("endMs",      .integer).notNull()
                t.column("textEn",     .text).notNull()
                t.column("textHangul", .text)
                t.column("textKo",     .text)
            }
            try db.create(index: "idx_segments_file", on: "segments",
                          columns: ["fileId", "idx"])
        }
        return m
    }

    // MARK: - DAO

    public func createProject(name: String) throws -> Project {
        let now = Date()
        let p = Project(id: UUID().uuidString, name: name, createdAt: now, updatedAt: now)
        try dbQueue.write { db in try p.insert(db) }
        return p
    }

    public func listProjects() throws -> [Project] {
        try dbQueue.read { db in
            try Project.order(Column("updatedAt").desc).fetchAll(db)
        }
    }

    public func deleteProject(id: String) throws {
        _ = try dbQueue.write { db in
            try Project.deleteOne(db, key: id)
        }
    }

    public func insertMediaFile(_ file: MediaFile) throws {
        try dbQueue.write { db in try file.insert(db) }
    }

    public func updateMediaFile(_ file: MediaFile) throws {
        try dbQueue.write { db in try file.update(db) }
    }

    public func listFiles(projectId: String) throws -> [MediaFile] {
        try dbQueue.read { db in
            try MediaFile
                .filter(Column("projectId") == projectId)
                .order(Column("createdAt").asc)
                .fetchAll(db)
        }
    }

    public func saveSegments(_ segments: [SegmentRecord], fileId: String) throws {
        try dbQueue.write { db in
            // Replace any existing segments for the file (simple semantics for the demo).
            try SegmentRecord.filter(Column("fileId") == fileId).deleteAll(db)
            for s in segments { try s.insert(db) }
        }
    }

    public func listSegments(fileId: String) throws -> [SegmentRecord] {
        try dbQueue.read { db in
            try SegmentRecord
                .filter(Column("fileId") == fileId)
                .order(Column("idx").asc)
                .fetchAll(db)
        }
    }
}
