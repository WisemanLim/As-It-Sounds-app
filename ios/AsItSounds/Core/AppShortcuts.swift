import AppIntents
import Foundation

/// "Hey Siri, transliterate <phrase>" — minimal App Intent + Shortcut bundle.
///
/// `AppShortcutsProvider` lives in the main app target; Siri picks it up
/// automatically when the app installs.
@available(iOS 17.0, *)
public struct AppShortcuts: AppShortcutsProvider {
    public static var appShortcuts: [AppShortcut] {
        // AppShortcut phrases only accept parameters of AppEnum / AppEntity
        // types. The TransliterateIntent's `text` is a free-form String, so we
        // expose it as a Siri-only intent (it shows up in Shortcuts.app
        // without a spoken trigger) and use the parameter-less
        // OpenLatestProjectIntent for the spoken shortcut.
        AppShortcut(
            intent: OpenLatestProjectIntent(),
            phrases: [
                "Open \(.applicationName)",
                "\(.applicationName) 열기",
            ],
            shortTitle: "Open As It Sounds",
            systemImageName: "waveform"
        )
    }
}

@available(iOS 17.0, *)
public struct TransliterateIntent: AppIntent {
    public static var title: LocalizedStringResource = "Transliterate text"
    public static var description = IntentDescription(
        "Returns the Korean Hangul phonetic spelling of the given text."
    )

    @Parameter(title: "Text", description: "English, Japanese, French, etc.")
    public var text: String

    public init() {}
    public init(text: String) { self.text = text }

    public func perform() async throws -> some IntentResult & ProvidesDialog & ReturnsValue<String> {
        let hangul = transliterate(text: text)
        return .result(value: hangul, dialog: IntentDialog(stringLiteral: hangul))
    }
}

/// Opens the app on the Library tab and shows the most-recent project.
@available(iOS 17.0, *)
public struct OpenLatestProjectIntent: AppIntent {
    public static var title: LocalizedStringResource = "Open latest project"
    public static var openAppWhenRun: Bool { true }

    public init() {}

    public func perform() async throws -> some IntentResult {
        // The app's deep-link / scene activation handles the actual open;
        // for Phase-3 we just bring the app forward.
        return .result()
    }
}
