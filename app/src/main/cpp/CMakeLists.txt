cmake_minimum_required(VERSION 3.22.1)
project("llama_android" C CXX)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 17)

# Disable unnecessary llama.cpp sub-targets for rapid mobile compilation
set(LLAMA_BUILD_TESTS OFF CACHE BOOL "llama: build tests" FORCE)
set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "llama: build examples" FORCE)
set(LLAMA_BUILD_SERVER OFF CACHE BOOL "llama: build server" FORCE)
set(LLAMA_BUILD_COMMON ON CACHE BOOL "llama: build common" FORCE)
set(LLAMA_CURL OFF CACHE BOOL "llama: curl support" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "build shared" FORCE)

# Use cached local llama.cpp clone if present, otherwise FetchContent from pinned tag
if (EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp/CMakeLists.txt")
    add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp build-llama EXCLUDE_FROM_ALL)
    set(llama_SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/llama.cpp")
else()
    include(FetchContent)
    FetchContent_Declare(
        llama
        GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
        GIT_TAG        b4100
        GIT_SHALLOW    TRUE
    )
    FetchContent_MakeAvailable(llama)
endif()

add_library(llama_android SHARED
    llama_android.cpp
)

target_include_directories(llama_android PRIVATE
    ${llama_SOURCE_DIR}/include
    ${llama_SOURCE_DIR}/common
    ${llama_SOURCE_DIR}/ggml/include
)

target_compile_options(llama_android PRIVATE
    -O3
    -Wall
    -Wextra
)

target_link_libraries(llama_android
    llama
    common
    android
    log
)
