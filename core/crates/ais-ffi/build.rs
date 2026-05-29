fn main() {
    uniffi::generate_scaffolding("src/ais_ffi.udl").expect("UniFFI scaffolding generation failed");
}
