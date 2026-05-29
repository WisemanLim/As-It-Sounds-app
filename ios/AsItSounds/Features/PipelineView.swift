import SwiftUI
import UniformTypeIdentifiers

struct PipelineView: View {
    @State private var settings = AppSettings.shared
    @State private var modelManager: ModelManager
    @State private var runner = PipelineRunner()

    init() {
        _modelManager = State(wrappedValue: ModelManager(model: AppSettings.shared.preferredModel))
    }

    @State private var showFileImporter = false
    @State private var selectedURL: URL?
    @State private var exportURL: URL?
    @State private var exportFormat: SubtitleFormat = .srt
    @State private var translatorChoice: TranslatorChoice = AppSettings.shared.defaultTranslator
    @State private var saveProject: Project?
    @State private var saveFile: MediaFile?

    var body: some View {
        NavigationStack {
            List {
                modelSection
                translatorSection
                fileSection
                runSection
                segmentsSection
            }
            .navigationTitle("pipeline.title")
            .fileImporter(
                isPresented: $showFileImporter,
                allowedContentTypes: [.audio, .movie, .mp3, .mpeg4Movie, .wav, .quickTimeMovie],
                allowsMultipleSelection: false
            ) { result in
                if case let .success(urls) = result, let u = urls.first {
                    // Scoped resource: hold while we transcribe.
                    _ = u.startAccessingSecurityScopedResource()
                    selectedURL = u
                }
            }
        }
    }

    // MARK: - Sections

    private var modelSection: some View {
        Section("pipeline.model.title") {
            switch modelManager.status {
            case .notDownloaded:
                Button {
                    modelManager.startDownload()
                } label: {
                    Label(
                        String(format: NSLocalizedString("pipeline.model.download", comment: ""),
                               modelManager.model.rawValue) + " (~142 MB)",
                        systemImage: "icloud.and.arrow.down"
                    )
                }
                .accessibilityLabel(LocalizedStringKey("a11y.model.download"))
            case .downloading(let received, let total):
                VStack(alignment: .leading) {
                    ProgressView(value: Double(received), total: Double(total))
                    Text("\(format(bytes: received)) / \(format(bytes: total))")
                        .font(.caption.monospacedDigit())
                }
            case .ready(let url):
                LabeledContent("Path") {
                    Text(url.lastPathComponent)
                        .font(.caption.monospaced())
                        .lineLimit(1)
                }
            case .failed(let msg):
                Label(msg, systemImage: "exclamationmark.triangle")
                    .foregroundStyle(.red)
            }
        }
    }

    private var translatorSection: some View {
        Section("pipeline.translator.title") {
            Picker(LocalizedStringKey("a11y.translator.picker"), selection: $translatorChoice) {
                ForEach(TranslatorChoice.allCases) { c in
                    Text(c.label).tag(c)
                }
            }
            .pickerStyle(.segmented)
            .accessibilityLabel(LocalizedStringKey("a11y.translator.picker"))
        }
    }

    private var fileSection: some View {
        Section("pipeline.file.title") {
            Button {
                showFileImporter = true
            } label: {
                Label(
                    selectedURL?.lastPathComponent ?? NSLocalizedString("pipeline.file.pick", comment: ""),
                    systemImage: "doc.badge.plus"
                )
            }
        }
    }

    private var runSection: some View {
        Section {
            Button {
                runPipeline()
            } label: {
                HStack {
                    Label("pipeline.run", systemImage: "play.fill")
                    Spacer()
                    stageBadge
                }
            }
            .disabled(!canRun)
            .accessibilityLabel(LocalizedStringKey("a11y.pipeline.run"))
        }
    }

    private var stageBadge: some View {
        Group {
            switch runner.stage {
            case .idle:             Text("pipeline.stage.ready")
            case .decoding:         Text("pipeline.stage.decoding")
            case .transcribing:     Text("pipeline.stage.asr")
            case .transliterating:  Text("pipeline.stage.translit")
            case .done:             Text("pipeline.stage.done")
            case .failed(let m):    Text(m).foregroundStyle(Color(.systemRed))
            }
        }
        .font(.caption.monospacedDigit())
        .foregroundStyle(.secondary)
    }

    @ViewBuilder
    private var segmentsSection: some View {
        if !runner.segments.isEmpty {
            Section("export.title") {
                Picker("export.title", selection: $exportFormat) {
                    ForEach(SubtitleFormat.allCases, id: \.self) { f in
                        Text(f.rawValue.uppercased()).tag(f)
                    }
                }
                .pickerStyle(.segmented)
                Button {
                    writeExportFile()
                } label: {
                    Label(
                        String(format: NSLocalizedString("export.save_as", comment: ""), exportFormat.rawValue),
                        systemImage: "square.and.arrow.up"
                    )
                }
                if let url = exportURL {
                    Label(url.lastPathComponent, systemImage: "doc.fill")
                        .font(.caption.monospaced())
                }
            }

            Section(String(format: NSLocalizedString("pipeline.segments.count", comment: ""), runner.segments.count)) {
                if let audioURL = selectedURL {
                    NavigationLink {
                        PlayerView(url: audioURL, segments: runner.segments)
                            .navigationTitle("pipeline.open.player")
                    } label: {
                        Label("pipeline.open.player", systemImage: "play.rectangle")
                    }
                }
                ForEach(runner.segments) { seg in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(format(ms: seg.startMs) + " — " + format(ms: seg.endMs))
                            .font(.caption.monospacedDigit())
                            .foregroundStyle(.secondary)
                        Text("EN  " + seg.textEn).font(.body)
                        Text("한  " + seg.textHangul)
                            .font(.body)
                            .foregroundStyle(Color.accentColor)
                        if !seg.textKo.isEmpty {
                            Text("KO  " + seg.textKo)
                                .font(.body)
                                .foregroundStyle(Color(.systemGreen))
                        }
                    }
                    .padding(.vertical, 4)
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel(String(
                        format: NSLocalizedString("a11y.segment.row", comment: ""),
                        seg.id + 1, format(ms: seg.startMs), seg.textEn, seg.textHangul
                    ))
                }
            }
        }
    }

    // MARK: - Helpers

    private var canRun: Bool {
        guard case .ready = modelManager.status else { return false }
        guard selectedURL != nil else { return false }
        switch runner.stage {
        case .idle, .done, .failed: return true
        default: return false
        }
    }

    private func runPipeline() {
        guard case .ready(let modelURL) = modelManager.status,
              let audioURL = selectedURL else { return }
        let translator = translatorChoice.make()

        // Create / reuse a project + file row so segments persist on completion.
        let db = AppDatabase.shared
        let project = saveProject ?? (try? db.createProject(name: "Session \(Date().formatted(date: .abbreviated, time: .shortened))"))
        if let project { saveProject = project }
        var file: MediaFile?
        if let project {
            let f = MediaFile(id: UUID().uuidString, projectId: project.id,
                              path: audioURL.path,
                              displayName: audioURL.lastPathComponent,
                              mime: nil, durationMs: nil,
                              status: .processing, error: nil, createdAt: Date())
            try? db.insertMediaFile(f)
            file = f
            saveFile = f
        }
        runner.persistFileId = file?.id

        Task {
            await runner.run(modelPath: modelURL.path, audioURL: audioURL, translator: translator)
            if var f = file {
                f.status = .done
                try? db.updateMediaFile(f)
            }
        }
    }

    private func format(bytes: Int64) -> String {
        let mb = Double(bytes) / (1024 * 1024)
        return String(format: "%.1f MB", mb)
    }

    private func writeExportFile() {
        guard !runner.segments.isEmpty else { return }
        let body = exportFormat.render(runner.segments)
        let base = selectedURL?.deletingPathExtension().lastPathComponent ?? "transcript"
        let docs = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0]
        let url = docs.appendingPathComponent("\(base).\(exportFormat.fileExtension)")
        do {
            try body.write(to: url, atomically: true, encoding: .utf8)
            exportURL = url
        } catch {
            exportURL = nil
        }
    }

    private func format(ms: Int64) -> String {
        let totalSec = ms / 1_000
        let m = totalSec / 60
        let s = totalSec % 60
        let cs = (ms % 1_000) / 10
        return String(format: "%02d:%02d.%02d", m, s, cs)
    }
}

#Preview {
    PipelineView()
}
