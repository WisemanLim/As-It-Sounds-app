import SwiftUI

struct LibraryView: View {
    @State private var projects: [Project] = []
    @State private var selectedProject: Project?
    @State private var showingNewProjectAlert = false
    @State private var newProjectName = ""

    var body: some View {
        NavigationStack {
            List {
                Section {
                    Button {
                        newProjectName = "New project"
                        showingNewProjectAlert = true
                    } label: {
                        Label("New project", systemImage: "plus.circle.fill")
                    }
                }
                Section("Projects") {
                    if projects.isEmpty {
                        Text("No projects yet.")
                            .foregroundStyle(.secondary)
                    }
                    ForEach(projects) { p in
                        NavigationLink {
                            ProjectDetailView(project: p)
                        } label: {
                            VStack(alignment: .leading) {
                                Text(p.name)
                                Text(p.updatedAt.formatted(date: .abbreviated, time: .shortened))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete(perform: deleteProjects)
                }
            }
            .navigationTitle("Library")
            .onAppear(perform: reload)
            .alert("Project name", isPresented: $showingNewProjectAlert) {
                TextField("Name", text: $newProjectName)
                Button("Create") { createProject() }
                Button("Cancel", role: .cancel) {}
            }
        }
    }

    private func reload() {
        projects = (try? AppDatabase.shared.listProjects()) ?? []
    }

    private func createProject() {
        let trimmed = newProjectName.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
        _ = try? AppDatabase.shared.createProject(name: trimmed)
        reload()
    }

    private func deleteProjects(at offsets: IndexSet) {
        for i in offsets {
            try? AppDatabase.shared.deleteProject(id: projects[i].id)
        }
        reload()
    }
}

struct ProjectDetailView: View {
    let project: Project
    @State private var files: [MediaFile] = []
    @State private var segmentsByFile: [String: [SegmentRecord]] = [:]

    var body: some View {
        List {
            Section("Files") {
                if files.isEmpty {
                    Text("No files yet. Transcribe one from the Transcribe tab to populate.")
                        .foregroundStyle(.secondary)
                }
                ForEach(files) { f in
                    DisclosureGroup {
                        let segs = segmentsByFile[f.id] ?? []
                        ForEach(segs) { s in
                            VStack(alignment: .leading, spacing: 2) {
                                Text("\(s.startMs)ms — \(s.endMs)ms")
                                    .font(.caption.monospacedDigit())
                                    .foregroundStyle(.secondary)
                                Text("EN  \(s.textEn)")
                                Text("한  \(s.textHangul ?? "")").foregroundStyle(.blue)
                                if let ko = s.textKo, !ko.isEmpty {
                                    Text("KO  \(ko)").foregroundStyle(.green)
                                }
                            }
                            .padding(.vertical, 2)
                        }
                    } label: {
                        VStack(alignment: .leading) {
                            Text(f.displayName)
                            Text(f.status.rawValue)
                                .font(.caption)
                                .foregroundStyle(f.status == .error ? Color.red : Color.secondary)
                            if let e = f.error, !e.isEmpty {
                                Text(e)
                                    .font(.caption2)
                                    .foregroundStyle(.red)
                            }
                        }
                    }
                }
            }
        }
        .navigationTitle(project.name)
        .onAppear(perform: reload)
    }

    private func reload() {
        files = (try? AppDatabase.shared.listFiles(projectId: project.id)) ?? []
        for f in files {
            segmentsByFile[f.id] = (try? AppDatabase.shared.listSegments(fileId: f.id)) ?? []
        }
    }
}
