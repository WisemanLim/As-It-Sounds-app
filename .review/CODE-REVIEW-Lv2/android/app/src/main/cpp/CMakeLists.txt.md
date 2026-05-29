# `android/app/src/main/cpp/CMakeLists.txt` — 코드 리뷰 / Code Review

> 리뷰 레벨: 2 · [← INDEX.md](../../../../../../INDEX.md)

**파일 요약**
- 역할: whisper.cpp 정적 라이브러리 빌드 + JNI bridge SHARED lib (`libais_whisper.so`) 빌드.
- LOC: 39
- 호출: AGP externalNativeBuild 가 자동 실행 (cmake 3.22.1)
- 산출: `app/build/intermediates/cxx/.../obj/<abi>/libais_whisper.so`

---

```cmake path=android/app/src/main/cpp/CMakeLists.txt lines=1-6
cmake_minimum_required(VERSION 3.22.1)
project(ais_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
```

**분석 (Level 2)**
- `cmake_minimum_required(VERSION 3.22.1)`: AGP 8.x 가 번들하는 CMake 와 일치. 더 낮으면 일부 modern 기능 미지원, 더 높으면 사용자 NDK 와 어긋남.
- `LANGUAGES C CXX`: whisper.cpp 가 C++17 본문 + 일부 C 파일 (ggml.c) 혼합. 둘 다 명시 안 하면 일부 .c 파일이 cxx 컴파일러로 잘못 빌드.
- `CMAKE_CXX_STANDARD 17 / STANDARD_REQUIRED ON`: -std=c++17 강제. fallback to c++14 차단.
- `CMAKE_POSITION_INDEPENDENT_CODE ON`: -fPIC 강제. 정적 lib (whisper) 가 공유 lib (ais_whisper.so) 에 link 되려면 PIC 필요. Android NDK 가 모든 lib PIC 빌드 권장.

```cmake path=android/app/src/main/cpp/CMakeLists.txt lines=8-16
# Path to the cloned whisper.cpp tree (sibling of the `android/` tree under
# `As-It-Sounds-app/core/third_party/whisper.cpp`).
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../../core/third_party/whisper.cpp)

if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}.\n"
        "Run: core/scripts/fetch-whisper.sh (or `git clone ... third_party/whisper.cpp`).")
endif()
```

**분석 (Level 2)**
- `CMAKE_SOURCE_DIR` = `android/app/src/main/cpp/` (이 CMakeLists.txt 디렉토리). 5단계 `../` = `core/third_party/whisper.cpp/`.
- `if(NOT EXISTS ... CMakeLists.txt)` + `message(FATAL_ERROR)`: 친절한 가드. 사용자가 첫 빌드에서 `git clone` 스킵하면 명확한 에러 메시지로 안내. CMake config 단계 (사실상 즉시) 에서 fail → 시간 낭비 없음.

```cmake path=android/app/src/main/cpp/CMakeLists.txt lines=18-26
# Whisper build knobs — keep small + fast for first Android smoke test.
# Acceleration (Vulkan, OpenCL, NNAPI) lands in Phase 1B.
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper EXCLUDE_FROM_ALL)
```

**분석 (Level 2)**
- 5개 `set(... CACHE BOOL "" FORCE)`: whisper.cpp 의 CMakeLists.txt 에 선언된 옵션을 **외부에서 override**. `CACHE BOOL "" FORCE` 가 핵심:
  - `CACHE BOOL` — 변수를 cache 에 저장 (sub-cmake 가 다시 정의 못함)
  - `""` — help string (UI 도구용, 무관)
  - `FORCE` — 이미 cache 에 값이 있어도 덮어씀
- `BUILD_SHARED_LIBS OFF`: whisper 가 .a (정적) 로 빌드 → ais_whisper.so 한 개로 모두 통합. .so 여러 개 분산 회피.
- `WHISPER_BUILD_{TESTS,EXAMPLES,SERVER} OFF`: 추가 타깃 비활성 — 컴파일 시간 ~50% 절감.
- `GGML_OPENMP OFF`: Android NDK 의 libomp 가 일부 디바이스에서 link error — 비활성 안전. CPU 멀티스레드는 ggml 자체 thread-pool 으로 처리.
- `add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper EXCLUDE_FROM_ALL)`:
  - 1번째 인자: source dir (whisper.cpp)
  - 2번째: binary dir (별도 build 산출 격리)
  - `EXCLUDE_FROM_ALL`: 본 ais_whisper 가 의존하는 타깃만 빌드 (`whisper` target). examples/tests 같은 다른 타깃이 cmake graph 에 등록되나 빌드 안 됨.

```cmake path=android/app/src/main/cpp/CMakeLists.txt lines=28-39
# JNI bridge
add_library(ais_whisper SHARED ais_whisper_jni.cpp)

target_include_directories(ais_whisper PRIVATE
    ${WHISPER_DIR}/include
    ${WHISPER_DIR}/ggml/include
)

# Link Android log + whisper static lib. whisper's CMakeLists exposes the
# `whisper` target; its own deps (ggml, ggml-cpu, ggml-base) propagate.
find_library(log-lib log)
target_link_libraries(ais_whisper PRIVATE whisper ${log-lib})
```

**분석 (Level 2)**
- `add_library(ais_whisper SHARED ...)`: `.so` (동적) 산출. AGP 가 `libais_whisper.so` 로 APK 에 패키징.
- `target_include_directories(... PRIVATE ...)`: ais_whisper.cpp 가 `#include "whisper.h"` + ggml 헤더만 사용. PRIVATE 라 외부에 헤더 전파 안 함.
- `find_library(log-lib log)`: Android NDK 의 `liblog.so` (Android logging) 검색. NDK 의 sysroot 안에 항상 있음. `${log-lib}` = `/path/to/log.so`.
- `target_link_libraries(... PRIVATE whisper ${log-lib})`: whisper static lib 의 transitive deps (ggml, ggml-cpu, ggml-base) 가 자동 전파됨 — whisper.cpp 의 CMakeLists 가 PUBLIC link 로 선언했기 때문.

**짚어볼 점 / Watch-outs**
- 가속 옵션 (Vulkan/OpenCL/NNAPI) 모두 비활성 — base 모델 RTF ~1.5 (CPU only) 가 Pixel 6 등 미드레인지에서 느림. Phase 1B 의 가속 옵션 활성은 `set(GGML_VULKAN ON ...)` 추가 + Vulkan SDK 확인 필요.
- whisper.cpp 의 `whisper` target 이 정적이지만 그 안에 ggml 의 정적들이 다시 들어가는데, **`-fPIC` 가 ggml 측에서도 켜져야** ais_whisper.so 의 PIC link 가능. ggml 의 CMakeLists 가 본 옵션을 propagate 하는지 사전 확인 필요. 현재 실제로 빌드 성공 — OK.
- `${log-lib}` 가 못 찾으면 `find_library` 가 `log-lib-NOTFOUND` 반환, target_link_libraries 에 그게 들어가서 link error. NDK 환경에서는 항상 성공.

**파일 수준 평가**
- 강점:
  - 39 LOC 로 whisper.cpp 통합 — `add_subdirectory` 패턴이 minimal.
  - whisper.cpp 변경 시 본 CMakeLists 수정 없이 자동 반영 (target 이름 `whisper` 유지되는 한).
  - 친절한 FATAL_ERROR 가드.
- 위험:
  - 가속 비활성 — Phase 1B 성능 목표 (RTF ≤ 0.5) 충족 어려움.
  - whisper.cpp upstream 의 target 명 변경 시 silent break.
- 개선 권고:
  1. `GGML_VULKAN ON` 시도 + Vulkan headers 가드:
     ```cmake
     find_package(Vulkan QUIET)
     if(Vulkan_FOUND)
         set(GGML_VULKAN ON CACHE BOOL "" FORCE)
     endif()
     ```
  2. whisper target alias 안정성을 위해 `add_subdirectory(...)` 직후 `if(NOT TARGET whisper) message(FATAL_ERROR "whisper.cpp target name changed") endif()` 추가.
- 재읽기 권고 레벨: 3
