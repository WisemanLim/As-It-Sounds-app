import SwiftUI

/// User preferences. Persists to UserDefaults — small surface, no need for
/// CoreData / GRDB for now. Cleared on app deletion.
@MainActor
@Observable
final class AppSettings {
    static let shared = AppSettings()

    var preferredModel: WhisperModel {
        get { WhisperModel(rawValue: defaults.string(forKey: keyModel) ?? "base") ?? .base }
        set { defaults.set(newValue.rawValue, forKey: keyModel) }
    }
    var defaultTranslator: TranslatorChoice {
        get { TranslatorChoice(rawValue: defaults.string(forKey: keyTranslator) ?? "myMemory") ?? .myMemory }
        set { defaults.set(newValue.rawValue, forKey: keyTranslator) }
    }
    var preferredColorScheme: ColorSchemePreference {
        get { ColorSchemePreference(rawValue: defaults.string(forKey: keyScheme) ?? "system") ?? .system }
        set { defaults.set(newValue.rawValue, forKey: keyScheme) }
    }

    private let defaults = UserDefaults.standard
    private let keyModel      = "ais.settings.model"
    private let keyTranslator = "ais.settings.translator"
    private let keyScheme     = "ais.settings.colorScheme"
}

public enum ColorSchemePreference: String, CaseIterable, Identifiable, Sendable {
    case system, light, dark
    public var id: String { rawValue }
    public var label: LocalizedStringKey {
        switch self {
        case .system: return "settings.dark_mode.auto"
        case .light:  return "settings.dark_mode.off"
        case .dark:   return "settings.dark_mode.on"
        }
    }
    public var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light:  return .light
        case .dark:   return .dark
        }
    }
}

struct SettingsView: View {
    @State private var settings = AppSettings.shared
    @State private var diskUsageMB: Int64 = -1

    var body: some View {
        NavigationStack {
            Form {
                Section("settings.model") {
                    Picker("settings.model", selection: $settings.preferredModel) {
                        ForEach(WhisperModel.allCases, id: \.self) { m in
                            Text(m.rawValue).tag(m)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("settings.translator") {
                    Picker("settings.translator", selection: $settings.defaultTranslator) {
                        ForEach(TranslatorChoice.allCases) { c in
                            Text(c.label).tag(c)
                        }
                    }
                }

                Section("settings.dark_mode") {
                    Picker("settings.dark_mode", selection: $settings.preferredColorScheme) {
                        ForEach(ColorSchemePreference.allCases) { p in
                            Text(p.label).tag(p)
                        }
                    }
                    .pickerStyle(.segmented)
                }

                Section("settings.about") {
                    LabeledContent("settings.version") {
                        Text(version)
                            .font(.caption.monospacedDigit())
                    }
                    NavigationLink("settings.licenses") { LicensesView() }
                }

                Section {
                    LabeledContent("settings.data.usage") {
                        Text(diskUsageMB < 0 ? "—" : "\(diskUsageMB) MB")
                    }
                    Button("settings.data.clear", role: .destructive) {
                        clearCache()
                    }
                }
            }
            .navigationTitle("settings.title")
            .task { diskUsageMB = computeDiskUsage() }
        }
    }

    private var version: String {
        let v = Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "?"
        let b = Bundle.main.infoDictionary?["CFBundleVersion"] as? String ?? "?"
        return "\(v) (\(b))"
    }

    private func computeDiskUsage() -> Int64 {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        guard let urls = try? FileManager.default.contentsOfDirectory(at: dir,
                                                                     includingPropertiesForKeys: [.fileSizeKey],
                                                                     options: [.skipsHiddenFiles]) else { return 0 }
        var total: Int64 = 0
        for url in urls {
            if let size = try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize {
                total += Int64(size)
            }
        }
        return total / (1024 * 1024)
    }

    private func clearCache() {
        let dir = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("models", isDirectory: true)
        try? FileManager.default.removeItem(at: dir)
        diskUsageMB = computeDiskUsage()
    }
}

struct LicensesView: View {
    var body: some View {
        ScrollView {
            Text(licenseText)
                .font(.caption.monospaced())
                .padding()
        }
        .navigationTitle("settings.licenses")
    }
    private let licenseText = """
    As It Sounds — MIT

    Bundled components:
      • whisper.cpp (MIT) — ggerganov/whisper.cpp
      • ggml (MIT) — ggerganov/ggml
      • GRDB.swift (MIT) — groue/GRDB.swift
      • UniFFI (MPL-2.0) — mozilla/uniffi-rs
      • Whisper model weights (MIT) — Hugging Face ggerganov/whisper.cpp

    Translation backends:
      • MyMemory — free tier (1000 words/day)
      • LibreTranslate — AGPLv3 server, user-hosted
      • DeepL — proprietary, user-supplied key
    """
}

#Preview { SettingsView() }
