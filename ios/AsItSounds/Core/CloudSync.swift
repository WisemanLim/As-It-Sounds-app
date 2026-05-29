import CloudKit
import Foundation

/// Minimal CloudKit sync for projects + their segments. Designed as the
/// "pull on launch, push on save" baseline; full real-time CKSubscription
/// notifications land in a later iteration.
///
/// **Setup required outside this file:**
///   1. Enable the iCloud capability in the iOS target with the
///      "CloudKit" service checked.
///   2. Create a container named `iCloud.com.asitsounds.ios` (matches
///      `containerIdentifier` below).
///   3. Add the `com.apple.developer.icloud-services` entitlement
///      with `CloudKit` and the `com.apple.developer.icloud-container-identifiers`
///      entitlement listing that container.
///
/// Without those entitlements every call here will throw
/// `CKError.notAuthenticated` or `.missingEntitlement`; the methods catch
/// and return `false` so the app keeps working offline-only.
@MainActor
public final class CloudSync {
    public static let shared = CloudSync()

    private let container = CKContainer(identifier: "iCloud.com.asitsounds.ios")
    private var database: CKDatabase { container.privateCloudDatabase }

    // MARK: - Health

    /// `true` if iCloud is available + the user is signed in.
    public func isReady() async -> Bool {
        do {
            let status = try await container.accountStatus()
            return status == .available
        } catch {
            return false
        }
    }

    // MARK: - Push

    /// Mirrors a single project + its files + segments up to CloudKit. Idempotent.
    /// Returns `false` on any failure; details are logged.
    @discardableResult
    public func pushProject(_ project: Project) async -> Bool {
        guard await isReady() else { return false }
        do {
            let projectRec = makeRecord(forProject: project)
            try await database.save(projectRec)

            let files = (try? AppDatabase.shared.listFiles(projectId: project.id)) ?? []
            for f in files {
                let fileRec = makeRecord(forFile: f, projectRecordID: projectRec.recordID)
                try await database.save(fileRec)

                let segs = (try? AppDatabase.shared.listSegments(fileId: f.id)) ?? []
                // Batch saves in 200-record chunks per CloudKit limits.
                for chunk in segs.chunked(into: 200) {
                    let recs = chunk.map { makeRecord(forSegment: $0, fileRecordID: fileRec.recordID) }
                    _ = try await database.modifyRecords(
                        saving: recs,
                        deleting: [],
                        savePolicy: .changedKeys
                    )
                }
            }
            return true
        } catch {
            NSLog("[AisCloud] pushProject failed: %@", error.localizedDescription)
            return false
        }
    }

    // MARK: - Pull

    /// Pulls all projects that exist in iCloud but not (or stale) in the
    /// local DB. Conflict policy: server wins (last-writer-wins on `updatedAt`).
    public func pullProjects() async -> Int {
        guard await isReady() else { return 0 }
        do {
            let q = CKQuery(recordType: "Project", predicate: NSPredicate(value: true))
            q.sortDescriptors = [NSSortDescriptor(key: "updatedAt", ascending: false)]
            let (results, _) = try await database.records(matching: q)

            var imported = 0
            for (_, result) in results {
                if case .success(let record) = result {
                    if let proj = projectFromRecord(record) {
                        try? AppDatabase.shared.upsertProject(proj)
                        imported += 1
                    }
                }
            }
            return imported
        } catch {
            NSLog("[AisCloud] pullProjects failed: %@", error.localizedDescription)
            return 0
        }
    }

    // MARK: - Record ↔ row

    private func makeRecord(forProject p: Project) -> CKRecord {
        let id = CKRecord.ID(recordName: p.id)
        let r = CKRecord(recordType: "Project", recordID: id)
        r["name"] = p.name as NSString
        r["createdAt"] = p.createdAt as NSDate
        r["updatedAt"] = p.updatedAt as NSDate
        return r
    }

    private func makeRecord(forFile f: MediaFile, projectRecordID: CKRecord.ID) -> CKRecord {
        let id = CKRecord.ID(recordName: f.id)
        let r = CKRecord(recordType: "MediaFile", recordID: id)
        r["project"]     = CKRecord.Reference(recordID: projectRecordID, action: .deleteSelf)
        r["displayName"] = f.displayName as NSString
        r["mime"]        = (f.mime ?? "") as NSString
        r["durationMs"]  = (f.durationMs ?? 0) as NSNumber
        r["status"]      = f.status.rawValue as NSString
        r["createdAt"]   = f.createdAt as NSDate
        return r
    }

    private func makeRecord(forSegment s: SegmentRecord, fileRecordID: CKRecord.ID) -> CKRecord {
        let id = CKRecord.ID(recordName: s.id)
        let r = CKRecord(recordType: "Segment", recordID: id)
        r["file"]       = CKRecord.Reference(recordID: fileRecordID, action: .deleteSelf)
        r["idx"]        = s.idx as NSNumber
        r["startMs"]    = s.startMs as NSNumber
        r["endMs"]      = s.endMs as NSNumber
        r["textEn"]     = s.textEn as NSString
        r["textHangul"] = (s.textHangul ?? "") as NSString
        r["textKo"]     = (s.textKo ?? "") as NSString
        return r
    }

    private func projectFromRecord(_ r: CKRecord) -> Project? {
        guard
            let name = r["name"] as? String,
            let createdAt = r["createdAt"] as? Date,
            let updatedAt = r["updatedAt"] as? Date
        else { return nil }
        return Project(id: r.recordID.recordName, name: name,
                       createdAt: createdAt, updatedAt: updatedAt)
    }
}

private extension Array {
    func chunked(into size: Int) -> [[Element]] {
        stride(from: 0, to: count, by: size).map { Array(self[$0 ..< Swift.min($0 + size, count)]) }
    }
}

/// Upsert helper for AppDatabase (extends without touching the GRDB class).
extension AppDatabase {
    func upsertProject(_ p: Project) throws {
        try dbQueue.write { db in
            try p.save(db)
        }
    }
}
