//! `ais-ffi` — UniFFI surface for Swift + Kotlin.

pub fn transliterate(text: String) -> String {
    ais_translit::transliterate(&text)
}

pub fn version() -> String {
    env!("CARGO_PKG_VERSION").to_string()
}

uniffi::include_scaffolding!("ais_ffi");
