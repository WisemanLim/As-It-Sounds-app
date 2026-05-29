cmake_minimum_required(VERSION 3.22.1)
project(ais_whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

# Path to the cloned whisper.cpp tree (sibling of the `android/` tree under
# `As-It-Sounds-app/core/third_party/whisper.cpp`).
set(WHISPER_DIR ${CMAKE_SOURCE_DIR}/../../../../../core/third_party/whisper.cpp)

if(NOT EXISTS ${WHISPER_DIR}/CMakeLists.txt)
    message(FATAL_ERROR
        "whisper.cpp not found at ${WHISPER_DIR}.\n"
        "Run: core/scripts/fetch-whisper.sh (or `git clone ... third_party/whisper.cpp`).")
endif()

# Whisper build knobs — keep small + fast for first Android smoke test.
# Acceleration (Vulkan, OpenCL, NNAPI) lands in Phase 1B.
set(BUILD_SHARED_LIBS  OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS    OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER   OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)

add_subdirectory(${WHISPER_DIR} ${CMAKE_BINARY_DIR}/whisper EXCLUDE_FROM_ALL)

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
