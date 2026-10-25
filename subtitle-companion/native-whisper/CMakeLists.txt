cmake_minimum_required(VERSION 3.22)
project(r15whisper LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

if(NOT DEFINED WHISPER_SOURCE_DIR)
  if(DEFINED ENV{WHISPER_SOURCE_DIR})
    set(WHISPER_SOURCE_DIR "$ENV{WHISPER_SOURCE_DIR}")
  else()
    message(FATAL_ERROR "Set WHISPER_SOURCE_DIR to a pinned whisper.cpp checkout")
  endif()
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "Embed GGML dependencies" FORCE)
include(FetchContent)
FetchContent_Declare(ggml SOURCE_DIR "${WHISPER_SOURCE_DIR}/ggml")
FetchContent_MakeAvailable(ggml)

add_library(r15whisper SHARED
  "${WHISPER_SOURCE_DIR}/src/whisper.cpp"
  "${WHISPER_SOURCE_DIR}/src/aneforge/whisper-aneforge.cpp"
  "${CMAKE_CURRENT_LIST_DIR}/native-whisper.cpp"
)

target_include_directories(r15whisper PRIVATE
  "${WHISPER_SOURCE_DIR}/include"
  "${WHISPER_SOURCE_DIR}/src"
  "${WHISPER_SOURCE_DIR}/ggml/include"
  "${WHISPER_SOURCE_DIR}/ggml/src"
  "${WHISPER_SOURCE_DIR}/ggml/src/ggml-cpu"
)
target_compile_definitions(r15whisper PRIVATE GGML_USE_CPU WHISPER_VERSION="1.9.5-dev")
target_compile_options(r15whisper PRIVATE -O3 -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_options(r15whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
target_link_libraries(r15whisper PRIVATE log android ggml)
