cmake_minimum_required(VERSION 3.22)
project(whisper_jni C CXX)

# whisper.cpp is fetched by the APK workflow (pinned tag) and passed in here, so
# none of its source is committed. Pass -DWHISPER_DIR=/path/to/whisper.cpp.
if (NOT WHISPER_DIR)
    message(FATAL_ERROR "Pass -DWHISPER_DIR=<path to a whisper.cpp checkout>")
endif()

set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
set(GGML_OPENMP OFF)
set(GGML_NATIVE OFF)
set(GGML_LLAMAFILE OFF)
# Cortex-A710/X2 (Exynos 2200) and every recent arm64 phone have dot-product
# and half-precision arithmetic.
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "")
add_subdirectory(${WHISPER_DIR} whisper.cpp-build EXCLUDE_FROM_ALL)

add_library(whisper_jni SHARED whisper_jni.cpp)
target_include_directories(whisper_jni PRIVATE ${WHISPER_DIR}/include ${WHISPER_DIR}/ggml/include)
target_link_libraries(whisper_jni PRIVATE whisper)
if (ANDROID)
    target_link_libraries(whisper_jni PRIVATE log)
    target_link_options(whisper_jni PRIVATE -Wl,-z,max-page-size=16384)
endif()
