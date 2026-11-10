cmake_minimum_required(VERSION 3.22.1)

project("mika_llama" VERSION 1.0.0 LANGUAGES C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_C_STANDARD_REQUIRED true)
set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED true)

# Cau hinh ggml cho arm64 giong ban Android chinh thuc cua llama.cpp
# (examples/llama.android): KleidiAI + OpenMP cho CPU backend.
if(DEFINED ANDROID_ABI AND ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_SYSTEM_ARCH "ARM")
    set(GGML_CPU_KLEIDIAI ON)
    set(GGML_OPENMP ON)
endif()

# Chi can llama + common; tat het tools/examples/tests/server cua llama.cpp.
set(LLAMA_BUILD_COMMON ON CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/llama.cpp build-llama)

add_library(mika_llama_jni SHARED
        mika_llama_jni.cpp)

target_include_directories(mika_llama_jni PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/common
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/ggml/include)

target_link_libraries(mika_llama_jni
        llama
        llama-common
        android
        log)
