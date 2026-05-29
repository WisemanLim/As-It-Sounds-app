# `core/scripts/build-android.sh` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../INDEX.md)

**파일 요약**
- 역할: cargo-ndk 로 Rust → 4 ABI `.so` 빌드 + Kotlin 바인딩 생성. `../android/app/src/main/jniLibs/<abi>/libais_ffi.so` + `../android/app/src/main/java/com/asitsounds/core/ais_ffi.kt` 출력.
- LOC: 54
- 호출: 사용자 manual 또는 CI `.github/workflows/android.yml`

---

```bash path=core/scripts/build-android.sh lines=1-23
#!/usr/bin/env bash
# Build ais-ffi for Android (4 ABIs) and emit Kotlin bindings.

set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="${HOME}/.cargo/bin:${PATH}"

: "${ANDROID_NDK_HOME:?ANDROID_NDK_HOME must be set (e.g. ~/Library/Android/sdk/ndk/27.x.x)}"

PROFILE="${PROFILE:-release}"
LIBNAME="libais_ffi.so"
ANDROID_APP_DIR="../android/app"
JNILIBS_DIR="${ANDROID_APP_DIR}/src/main/jniLibs"
KOTLIN_OUT="${ANDROID_APP_DIR}/src/main/java"

TARGETS=(
    aarch64-linux-android
    armv7-linux-androideabi
    x86_64-linux-android
    i686-linux-android
)
```

**분석 (Level 2)**
- `: "${ANDROID_NDK_HOME:?...}"`: bash 의 `:?word` 파라미터 확장 — 변수가 unset/empty 면 word 메시지 출력 후 즉시 종료. `set -u` 와 redundant 하지만 친절한 에러 메시지 제공. 첫 시도 사용자가 미설정 환경에서 명확한 안내 받음.
- 4 ABI 타겟:
  - `aarch64-linux-android` → arm64-v8a (현대 디바이스 99%)
  - `armv7-linux-androideabi` → armeabi-v7a (구형 32-bit, Android Go)
  - `x86_64-linux-android` → x86_64 (에뮬레이터, Chromebook)
  - `i686-linux-android` → x86 (사실상 사장, 호환 위해 포함)
- 4종 모두 Android NDK r25+ 가 지원. arm64-v8a 외 3종은 출시 시 ABI splits 로 별도 APK 분리됨.

```bash path=core/scripts/build-android.sh lines=24-43
echo "==> Ensuring cargo-ndk is installed"
if ! command -v cargo-ndk >/dev/null 2>&1; then
    cargo install --locked cargo-ndk
fi

echo "==> Ensuring Android Rust targets are installed"
for t in "${TARGETS[@]}"; do
    rustup target add "$t"
done

echo "==> Building ais-ffi for Android (4 ABIs)"
mkdir -p "${JNILIBS_DIR}"
cargo ndk \
    -t arm64-v8a \
    -t armeabi-v7a \
    -t x86_64 \
    -t x86 \
    -o "${JNILIBS_DIR}" \
    build --package ais-ffi --profile "$PROFILE"
```

**분석 (Level 2)**
- `command -v cargo-ndk >/dev/null 2>&1`: bash idiom for "이미 설치된 binary 인가?". `command -v` 는 alias/function/builtin/binary 모두 포함 검사. `>/dev/null 2>&1` 으로 출력 묵음.
- `cargo install --locked cargo-ndk`: `--locked` 가 `Cargo.lock` 의 deps 그대로 설치 — 재현성 보장. 없으면 매번 최신 sub-dep 으로 install → CI 환경에서 갑작스러운 호환성 break 가능.
- `cargo ndk -t <abi> ... -o <out> build --package ais-ffi --profile release`: cargo-ndk 가 cargo build wrapper. 각 ABI 마다:
  - `CC_<target>` / `CXX_<target>` / `AR_<target>` / `LINKER_<target>` 환경변수를 NDK 의 clang/llvm-ar 로 자동 설정
  - `cargo build --target <rust-target>` 호출
  - 산출 `target/<rust-target>/release/libais_ffi.so` 를 `<out>/<android-abi>/libais_ffi.so` 로 복사
- 4 ABI 순차 실행 — 병렬화 안됨. 첫 빌드 ~10–15분 (Apple Silicon Mac). 후속 incremental 1–2분.

```bash path=core/scripts/build-android.sh lines=45-54
echo "==> Generating Kotlin bindings"
cargo run --release --features cli --bin uniffi-bindgen -- \
    generate crates/ais-ffi/src/ais_ffi.udl \
    --language kotlin \
    --out-dir "${KOTLIN_OUT}" \
    --config crates/ais-ffi/uniffi.toml

echo
echo "✓ Native libs:    ${JNILIBS_DIR}/<abi>/${LIBNAME}"
echo "✓ Kotlin binding: ${KOTLIN_OUT}/com/asitsounds/core/*.kt"
```

**분석 (Level 2)**
- iOS 스크립트의 swift 생성 라인과 거의 동일 — `--language kotlin` 만 변경. uniffi.toml 의 `[bindings.kotlin].package_name = "com.asitsounds.core"` 가 생성 디렉토리를 `${KOTLIN_OUT}/com/asitsounds/core/` 로 자동 매핑.
- `cdylib_name = "ais_ffi"` (uniffi.toml) → Kotlin 측에서 `System.loadLibrary("ais_ffi")` 호출 → JVM 이 ABI 매핑 자동 (lib<name>.so → load <name>).

**짚어볼 점 / Watch-outs**
- `cargo ndk` 가 ABI 명을 Android 식 (`arm64-v8a` 등) 으로 받음. 위 `TARGETS` 배열의 Rust 타겟 명 (`aarch64-linux-android` 등) 은 `rustup target add` 만을 위한 것. **두 배열이 동기화 깨지면 silent: cargo ndk 는 `-t arm64-v8a` 만 보고 Rust target 자동 매핑**. 별도 sanity 가드 없음.
- `--profile release` 가 `Cargo.toml [profile.release]` 의 `opt-level="z"` + LTO 적용 → arm64-v8a `.so` 약 690 KB.
- `set -u` + `${VAR:?}` 조합으로 환경변수 misuse 시 즉시 fail — 신뢰성 ↑.
- bash 3.2 (macOS 기본) 에서 `declare -A` 가 깨지는 함정은 본 스크립트가 회피함 (단순 array + index 만 사용). 이전 버전에 있던 `declare -A ABI_DIR` 가 삭제된 흔적은 INDEX.md 의 build-android.sh history 노트에 있음.
- 4 ABI 순차 빌드 — cargo-ndk 가 -j 병렬 미지원. 향후 GNU parallel 등으로 wrapping 가능하지만 cargo 자체의 incremental cache 효과 더 크므로 우선순위 낮음.

**파일 수준 평가**
- 강점:
  - `${VAR:?}` 가드, `command -v` idempotent 설치, `cargo install --locked` 재현성 — 빌드 안정성 견고.
  - iOS 스크립트와 대칭 구조 — 유지보수 시 mental model 공유 쉬움.
- 위험:
  - cargo-ndk ABI 명 ↔ rustup target 명 매핑이 hard-code, drift 가능.
  - 첫 사용자 가 NDK 미설치 시 친절한 안내 부족 (현재는 cargo-ndk 가 NDK_HOME 가드만).
- 개선 권고:
  1. ABI ↔ target 매핑 검증 step 추가:
     ```bash
     declare -A check_map=([arm64-v8a]=aarch64-linux-android [armeabi-v7a]=armv7-linux-androideabi ...)
     # 또는 array 두 개 짝맞춤 검증
     ```
     (macOS bash 3.2 에서는 parallel array)
  2. NDK 버전 sanity: `"$ANDROID_NDK_HOME"/source.properties` grep 으로 r25+ 검증.
- 재읽기 권고 레벨: 2
