import SwiftUI

@Observable
@MainActor
final class TranslitViewModel {
    var input: String = "As it sounds, it works."
    var output: String = ""
    var coreVersion: String = ""
    var whisperInfo: String = ""

    func run() {
        output = transliterate(text: input)
        coreVersion = version()
        whisperInfo = WhisperEngine.systemInfo()
    }
}

struct TranslitDemoView: View {
    @State private var vm = TranslitViewModel()

    var body: some View {
        NavigationStack {
            Form {
                Section("English / French / Japanese / …") {
                    TextEditor(text: $vm.input)
                        .frame(minHeight: 120)
                        .font(.body.monospaced())
                }

                Section {
                    Button("Transliterate", systemImage: "wand.and.stars") {
                        vm.run()
                    }
                    .buttonStyle(.borderedProminent)
                }

                Section("한글 음차 (Hangul)") {
                    Text(vm.output.isEmpty ? "—" : vm.output)
                        .font(.title3)
                        .textSelection(.enabled)
                }

                Section {
                    LabeledContent("Core version", value: vm.coreVersion.isEmpty ? "—" : vm.coreVersion)
                }

                Section("Whisper backend") {
                    Text(vm.whisperInfo.isEmpty ? "—" : vm.whisperInfo)
                        .font(.caption.monospaced())
                        .textSelection(.enabled)
                }
            }
            .navigationTitle("As It Sounds")
            .task { vm.run() }
        }
    }
}

#Preview {
    TranslitDemoView()
}
