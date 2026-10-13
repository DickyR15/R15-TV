cmake_minimum_required(VERSION 3.22)
project(r15_opencc_android LANGUAGES CXX)

if(NOT DEFINED OPENCC_SOURCE_DIR OR NOT EXISTS "${OPENCC_SOURCE_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "Set OPENCC_SOURCE_DIR to the checked-out official BYVoid/OpenCC source tree.")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(OPENCC_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(ENABLE_GTEST OFF CACHE BOOL "" FORCE)
set(ENABLE_BENCHMARK OFF CACHE BOOL "" FORCE)
set(BUILD_DOCUMENTATION OFF CACHE BOOL "" FORCE)
set(BUILD_PYTHON OFF CACHE BOOL "" FORCE)
set(BUILD_OPENCC_JIEBA_PLUGIN OFF CACHE BOOL "" FORCE)
set(OPENCC_DICT_FORMAT "ocd2" CACHE STRING "" FORCE)

# Build against the official pinned OpenCC source, but do not build its host CLI
# or dictionary-generation targets as part of the Android ABI build.
add_subdirectory("${OPENCC_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/opencc" EXCLUDE_FROM_ALL)

add_library(r15opencc SHARED opencc_jni.cpp)
target_compile_features(r15opencc PRIVATE cxx_std_17)
target_include_directories(r15opencc PRIVATE "${OPENCC_SOURCE_DIR}/src")
target_link_libraries(r15opencc PRIVATE OpenCC::OpenCC log)
set_target_properties(r15opencc PROPERTIES OUTPUT_NAME "r15opencc")
