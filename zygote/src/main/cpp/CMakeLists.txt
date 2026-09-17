cmake_minimum_required(VERSION 3.28)
project(duckusb CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

get_filename_component(REPO_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/../../../.." ABSOLUTE)

set(LSPLANT_BUILD_SHARED OFF CACHE BOOL "" FORCE)
set(LSPLANT_STANDALONE OFF CACHE BOOL "" FORCE)
add_subdirectory(${REPO_ROOT}/external/LSPlant/lsplant/src/main/jni lsplant)

set(DOBBY_GENERATE_SHARED OFF CACHE BOOL "" FORCE)
set(BUILD_EXAMPLE OFF CACHE BOOL "" FORCE)
set(BUILD_TEST OFF CACHE BOOL "" FORCE)
set(DOBBY_DEBUG OFF CACHE BOOL "" FORCE)
set(Plugin.SymbolResolver ON CACHE BOOL "" FORCE)
add_subdirectory(${REPO_ROOT}/external/Dobby dobby)

add_library(duckusb SHARED
        native_hooks.cpp
        lsplant_glue.cpp
        elf_img.cpp)

target_include_directories(duckusb PRIVATE
        ${REPO_ROOT}/external/Dobby/include
        ${REPO_ROOT}/external/LSPlant/lsplant/src/main/jni/include)

target_compile_options(duckusb PRIVATE -Wall -fvisibility=hidden)
target_link_options(duckusb PRIVATE -Wl,--exclude-libs,ALL)

target_link_libraries(duckusb log lsplant_static dobby)
