//! `ais-translit` — re-exports the desktop project's transliteration engine without copying.
//!
//! The single authoritative source is the desktop repo's [`translit.rs`]. We pull it in via
//! `#[path]` so the iOS + Android apps consume the exact same code path the desktop and web
//! versions use. Update the constant below if the desktop repo moves on disk.
//!
//! [`translit.rs`]: ../../../../../../NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs

#[path = "../../../../../../NGL(Go,Rust,...)/As-It-Sounds/src-tauri/src/core/translit.rs"]
mod translit_upstream;

pub use translit_upstream::transliterate;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn english_basic() {
        let out = transliterate("As it sounds, it works.");
        assert!(!out.is_empty(), "empty output");
    }

    #[test]
    fn empty_input() {
        assert_eq!(transliterate(""), "");
    }

    #[test]
    fn passthrough_korean() {
        let s = "안녕하세요";
        assert_eq!(transliterate(s), s);
    }
}
