cmake_minimum_required(VERSION 3.28)
project(duckusb C CXX)

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

set(XZ_DIR ${REPO_ROOT}/external/xz-embedded)
add_library(xz STATIC
        ${XZ_DIR}/linux/lib/xz/xz_crc32.c
        ${XZ_DIR}/linux/lib/xz/xz_crc64.c
        ${XZ_DIR}/linux/lib/xz/xz_dec_stream.c
        ${XZ_DIR}/linux/lib/xz/xz_dec_lzma2.c
        ${XZ_DIR}/linux/lib/xz/xz_dec_bcj.c)
target_compile_definitions(xz PUBLIC XZ_USE_CRC64 XZ_DEC_ANY_CHECK XZ_DEC_ARM64 XZ_DEC_X86)
target_include_directories(xz PUBLIC ${XZ_DIR}/userspace ${XZ_DIR}/linux/include/linux)
target_compile_options(xz PRIVATE -Wno-everything)

add_library(duckusb SHARED
        lsplant_glue.cpp
        elf_img.cpp)

target_include_directories(duckusb PRIVATE
        ${REPO_ROOT}/external/Dobby/include
        ${REPO_ROOT}/external/LSPlant/lsplant/src/main/jni/include)

target_compile_options(duckusb PRIVATE -Wall -fvisibility=hidden)
target_link_options(duckusb PRIVATE -Wl,--exclude-libs,ALL)

target_link_libraries(duckusb log lsplant_static dobby xz)
