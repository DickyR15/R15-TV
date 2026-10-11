cmake_minimum_required(VERSION 3.22)
project(r15whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR $ENV{WHISPER_SOURCE_DIR}/ggml)
FetchContent_MakeAvailable(ggml)

add_library(r15whisper SHARED
    $ENV{WHISPER_SOURCE_DIR}/src/whisper.cpp
    $ENV{WHISPER_SOURCE_DIR}/src/aneforge/whisper-aneforge.cpp
    $ENV{GITHUB_WORKSPACE}/patches/whisper-native/native-whisper.cpp
)

target_include_directories(r15whisper PRIVATE
    $ENV{WHISPER_SOURCE_DIR}/include
    $ENV{WHISPER_SOURCE_DIR}/src
    $ENV{WHISPER_SOURCE_DIR}/ggml/include
    $ENV{WHISPER_SOURCE_DIR}/ggml/src
    $ENV{WHISPER_SOURCE_DIR}/ggml/src/ggml-cpu
)

target_compile_definitions(r15whisper PRIVATE GGML_USE_CPU)
target_compile_options(r15whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(r15whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(r15whisper PRIVATE log android ggml)
