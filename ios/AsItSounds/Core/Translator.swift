import Foundation

public protocol Translator: Sendable {
    var name: String { get }
    var requiresNetwork: Bool { get }
    /// Translates `text` from `src` (ISO-639-1, e.g. "en") to `dst`.
    /// On failure, throw — callers fall back to a no-op string.
    func translate(_ text: String, from src: String, to dst: String) async throws -> String
}

// MARK: - Disabled (no-op fallback)

public struct DisabledTranslator: Translator {
    public let name = "Disabled"
    public let requiresNetwork = false
    public init() {}
    public func translate(_ text: String, from src: String, to dst: String) async throws -> String {
        ""
    }
}

// MARK: - MyMemory (free, no key)

public struct MyMemoryTranslator: Translator {
    public let name = "MyMemory"
    public let requiresNetwork = true
    public init() {}

    public func translate(_ text: String, from src: String, to dst: String) async throws -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        var comps = URLComponents(string: "https://api.mymemory.translated.net/get")!
        comps.queryItems = [
            URLQueryItem(name: "q", value: trimmed),
            URLQueryItem(name: "langpair", value: "\(src)|\(dst)"),
        ]
        let (data, _) = try await URLSession.shared.data(from: comps.url!)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let responseData = json["responseData"] as? [String: Any],
            let translated = responseData["translatedText"] as? String
        else {
            throw NSError(domain: "MyMemory", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "unexpected response shape"])
        }
        return translated
    }
}

// MARK: - LibreTranslate (self-host or libretranslate.com)

public struct LibreTranslateTranslator: Translator {
    public let name = "LibreTranslate"
    public let requiresNetwork = true
    public let endpoint: URL
    public let apiKey: String?

    public init(endpoint: URL = URL(string: "https://libretranslate.com/translate")!,
                apiKey: String? = nil) {
        self.endpoint = endpoint
        self.apiKey = apiKey
    }

    public func translate(_ text: String, from src: String, to dst: String) async throws -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }

        var req = URLRequest(url: endpoint)
        req.httpMethod = "POST"
        req.setValue("application/json", forHTTPHeaderField: "Content-Type")

        var body: [String: Any] = [
            "q": trimmed,
            "source": src,
            "target": dst,
            "format": "text",
        ]
        if let apiKey { body["api_key"] = apiKey }
        req.httpBody = try JSONSerialization.data(withJSONObject: body)

        let (data, _) = try await URLSession.shared.data(for: req)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let translated = json["translatedText"] as? String
        else {
            throw NSError(domain: "LibreTranslate", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "unexpected response shape"])
        }
        return translated
    }
}

// MARK: - DeepL (free or pro, API key required)

public struct DeepLTranslator: Translator {
    public let name = "DeepL"
    public let requiresNetwork = true
    public let apiKey: String
    public let isFreeTier: Bool

    public init(apiKey: String, isFreeTier: Bool = true) {
        self.apiKey = apiKey
        self.isFreeTier = isFreeTier
    }

    public func translate(_ text: String, from src: String, to dst: String) async throws -> String {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return "" }
        let host = isFreeTier ? "api-free.deepl.com" : "api.deepl.com"
        var comps = URLComponents()
        comps.scheme = "https"
        comps.host = host
        comps.path = "/v2/translate"

        var req = URLRequest(url: comps.url!)
        req.httpMethod = "POST"
        req.setValue("DeepL-Auth-Key \(apiKey)", forHTTPHeaderField: "Authorization")
        req.setValue("application/x-www-form-urlencoded", forHTTPHeaderField: "Content-Type")

        let form = [
            ("text",        trimmed),
            ("source_lang", src.uppercased()),
            ("target_lang", dst.uppercased()),
        ]
        req.httpBody = form
            .map { "\($0.0)=\(percentEncoded($0.1))" }
            .joined(separator: "&")
            .data(using: .utf8)

        let (data, _) = try await URLSession.shared.data(for: req)
        guard
            let json = try JSONSerialization.jsonObject(with: data) as? [String: Any],
            let translations = json["translations"] as? [[String: Any]],
            let first = translations.first,
            let text = first["text"] as? String
        else {
            throw NSError(domain: "DeepL", code: 1,
                          userInfo: [NSLocalizedDescriptionKey: "unexpected response shape"])
        }
        return text
    }

    private func percentEncoded(_ s: String) -> String {
        var allowed = CharacterSet.urlQueryAllowed
        allowed.remove(charactersIn: "+&=")
        return s.addingPercentEncoding(withAllowedCharacters: allowed) ?? s
    }
}

// MARK: - Selection

public enum TranslatorChoice: String, CaseIterable, Identifiable, Sendable {
    case disabled, myMemory, libreTranslate, deepL

    public var id: String { rawValue }
    public var label: String {
        switch self {
        case .disabled:        return "Off"
        case .myMemory:        return "MyMemory"
        case .libreTranslate:  return "LibreTranslate"
        case .deepL:           return "DeepL"
        }
    }

    public func make() -> any Translator {
        switch self {
        case .disabled:       return DisabledTranslator()
        case .myMemory:       return MyMemoryTranslator()
        case .libreTranslate: return LibreTranslateTranslator()
        case .deepL:          return DeepLTranslator(apiKey: "")
        }
    }
}
