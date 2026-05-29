# `core/scripts/build-ios.sh` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../INDEX.md)

**파일 요약**
- 역할: iOS 3 target × Rust → lipo → xcframework 조립 + Swift bindings 생성. 1회 호출로 `AisCoreFFI.xcframework` + `AisCoreFFI.swift` 완성.
- LOC: 62
- 호출: 사용자 manual (`./scripts/build-ios.sh`) 또는 CI `.github/workflows/ios.yml`
- 부수효과: 디스크 ~150 MB, `target/aarch64-apple-ios{,-sim}/release/`, `target/x86_64-apple-ios/release/`, `target/lipo-ios-sim/`, `target/AisCoreFFI.xcframework`, `bindings/swift/` 작성

---

```bash path=core/scripts/build-ios.sh lines=1-13
#!/usr/bin/env bash
# Build ais-ffi for iOS device + simulator and assemble an XCFramework.
# Output: core/target/AisCoreFFI.xcframework

set -euo pipefail
cd "$(dirname "$0")/.."

export PATH="${HOME}/.cargo/bin:${PATH}"

PROFILE="${PROFILE:-release}"
LIBNAME="libais_ffi.a"
FFI_NAME="AisCoreFFI"
```

**분석 (Level 2)**
- `set -euo pipefail`: bash 의 4종 안전 모드:
  - `-e` 실패 시 즉시 종료
  - `-u` undefined 변수 참조 시 즉시 종료
  - `-o pipefail` 파이프 중간 실패도 종료 코드로 전파
- `cd "$(dirname "$0")/.."`: 스크립트 자체 위치 기준 상대 이동. 결과 cwd = `core/`. 호출자가 어디서 호출하든 결과 일관.
- `export PATH="${HOME}/.cargo/bin:${PATH}"`: rustup 이 설치한 cargo 가 PATH 에 없을 때 (예: GUI 에서 launch 한 Xcode 의 환경) fallback. PATH prepend 라 cargo 가 우선됨.
- `PROFILE="${PROFILE:-release}"`: 환경변수 override 가능. `PROFILE=debug ./scripts/build-ios.sh` 로 디버그 빌드 실험 허용. `:-` 는 unset 또는 empty 일 때만 기본값 적용.
- `LIBNAME` / `FFI_NAME` 상수: hard-code 회피.

```bash path=core/scripts/build-ios.sh lines=14-28
TARGETS=(
    aarch64-apple-ios
    aarch64-apple-ios-sim
    x86_64-apple-ios
)

echo "==> Ensuring iOS Rust targets are installed"
for t in "${TARGETS[@]}"; do
    rustup target add "$t"
done

echo "==> Building ais-ffi for each iOS target"
for t in "${TARGETS[@]}"; do
    cargo build --package ais-ffi --target "$t" --profile "$PROFILE"
done
```

**분석 (Level 2)**
- 3 target 배열:
  - `aarch64-apple-ios` — iPhone/iPad 실기 (Apple Silicon)
  - `aarch64-apple-ios-sim` — Apple Silicon Mac 시뮬레이터
  - `x86_64-apple-ios` — Intel Mac 시뮬레이터 (구형 macOS Intel CI 호환)
- `rustup target add` 가 idempotent — 이미 설치되면 즉시 return. 매 빌드 추가 비용 무시 가능.
- `cargo build --package ais-ffi --target ...` 가 path-aware 워크스페이스 dep (`ais-translit`) 까지 함께 빌드함. 3 target × 워크스페이스 전체 = 첫 빌드 ~5–10분 (Apple Silicon).

```bash path=core/scripts/build-ios.sh lines=29-46
echo "==> Generating Swift bindings"
cargo run --release --features cli --bin uniffi-bindgen -- \
    generate crates/ais-ffi/src/ais_ffi.udl \
    --language swift \
    --out-dir bindings/swift \
    --config crates/ais-ffi/uniffi.toml

# UniFFI emits AisCoreFFI.{swift,h,modulemap}. Xcode auto-picks `module.modulemap`,
# so rename it for the framework search path to work without -fmodule-map-file.
mv -f "bindings/swift/${FFI_NAME}.modulemap" "bindings/swift/module.modulemap"

echo "==> Combining simulator slices (arm64 + x86_64) with lipo"
mkdir -p target/lipo-ios-sim
lipo -create \
    "target/aarch64-apple-ios-sim/${PROFILE}/${LIBNAME}" \
    "target/x86_64-apple-ios/${PROFILE}/${LIBNAME}" \
    -output "target/lipo-ios-sim/${LIBNAME}"
```

**분석 (Level 2)**
- `cargo run --release --features cli --bin uniffi-bindgen -- ...`: 본 워크스페이스의 `ais-ffi` crate 가 가진 bin target (`required-features = ["cli"]`) 을 실행. 첫 호출 시 uniffi + uniffi_bindgen + clap 등 cli 의존성 약 80 crate 가 컴파일됨 → 2–3분 추가.
- `generate ais_ffi.udl --language swift --out-dir ... --config ...uniffi.toml`: UDL + uniffi.toml 두 파일을 입력으로 Swift 바인딩 1개 (`AisCoreFFI.swift` ~16 KB) + C 헤더 (`AisCoreFFI.h`) + module map (`AisCoreFFI.modulemap`) 3 파일 생성.
- `mv -f ...AisCoreFFI.modulemap ... module.modulemap`: Xcode 가 framework search path 안의 `module.modulemap` 만 자동 인식. 이름 변경으로 `-fmodule-map-file` 명시 옵션 불필요. `-f` 가 dest 가 존재하면 overwrite — 두 번째 실행도 안전.
- `lipo -create ... -output`: Apple 의 universal binary 도구. 2 sim slice (arm64 + x86_64) → 단일 fat .a. xcframework 가 sim/device 두 큰 slice 만 받으므로 sim 2-arch 를 하나로 합치는 게 표준 패턴.

```bash path=core/scripts/build-ios.sh lines=48-62
echo "==> Assembling XCFramework"
rm -rf "target/${FFI_NAME}.xcframework"
xcodebuild -create-xcframework \
    -library "target/aarch64-apple-ios/${PROFILE}/${LIBNAME}" \
    -headers bindings/swift \
    -library "target/lipo-ios-sim/${LIBNAME}" \
    -headers bindings/swift \
    -output "target/${FFI_NAME}.xcframework"

echo
echo "✓ Output: $(pwd)/target/${FFI_NAME}.xcframework"
echo "✓ Swift binding: bindings/swift/${FFI_NAME}.swift"
echo
echo "Next: drag both into the Xcode project (or run xcodegen)."
```

**분석 (Level 2)**
- `rm -rf` 명시: stale slice 가 남아 있으면 `-create-xcframework` 가 "library already exists" 로 fail. 매 실행 cleanup.
- `xcodebuild -create-xcframework` 가 device slice + lipo'd sim slice 두 -library 인자 + 동일 -headers 폴더 두 번 받음. headers 폴더 안에 `AisCoreFFI.h` + `module.modulemap` + `AisCoreFFI.swift` 가 함께 들어가는데, xcframework 는 .h + module.modulemap 만 헤더로 인식, .swift 는 별도 source 로 Xcode 에 add 되는 패턴 (xcodegen project.yml 에서 `path: ../core/bindings/swift/AisCoreFFI.swift` 로 source 등록).

**짚어볼 점 / Watch-outs**
- `xcodebuild` 명령이 `xcode-select` 가 `/Library/Developer/CommandLineTools` 가리킬 때 실패 — README 단계 1 에서 명시했지만, 첫 실행 사용자 trap. 스크립트 시작부에 `xcode-select -p` 가드 추가 권고.
- `cd "$(dirname "$0")/.."` 가 경로에 괄호 포함 시 안전한지: bash 의 단어 분리 규칙은 따옴표 안에서 동작 X. ✓
- `lipo -create` 가 slice 호환성 (cpu type) 자동 검증 — 다른 arch 끼리 합칠 때만 성공. arm64 sim + x86_64 sim 모두 simulator platform, 다른 cpu — 정상.
- `--profile "$PROFILE"` 가 `--release` 와 동등 (release profile). bash 변수 안의 reserved value 직접 사용 — cargo 가 `release` / `dev` / 임의 custom profile 받음. 본 스크립트는 release/debug 만 의도된 듯.
- visionOS / tvOS slice 미포함 — 데스크탑 `build-xcframework.sh` 와 달리 본 스크립트는 iOS-only. 적절한 scope 축소.

**파일 수준 평가**
- 강점:
  - `set -euo pipefail` + 일관된 cwd 이동 + 환경변수 기본값 패턴 — bash best practice 표준.
  - lipo + xcframework + UniFFI bindgen 3 단계가 62 LOC 로 깔끔.
- 위험:
  - `mv -f` 단계가 첫 실행 시 (modulemap 부재) silently skip 가능 — 두 번째 실행 가서야 의도된 rename 발생. 다행히 첫 실행에서도 UniFFI 가 `AisCoreFFI.modulemap` 을 항상 생성하므로 OK.
  - xcode-select 가드 부재.
- 개선 권고:
  1. 스크립트 시작부 가드:
     ```bash
     if ! xcode-select -p | grep -q '/Applications/Xcode'; then
         echo "ERROR: xcode-select points at CommandLineTools — switch via 'sudo xcode-select -s /Applications/Xcode.app/Contents/Developer'"; exit 1
     fi
     ```
  2. lipo 산출 후 `lipo -info target/lipo-ios-sim/libais_ffi.a` 로 arch 검증 step 추가.
  3. `cargo run --release --features cli` 가 매 호출마다 80 crate 재빌드 시도 — `.cargo/config.toml` 에 `[build] target-dir = "target"` 명시 + CI 캐시 hit 검토.
- 재읽기 권고 레벨: 2 (Level 3 은 lipo/xcframework 내부 매니페스트 구조까지 들여다보면 가치)
