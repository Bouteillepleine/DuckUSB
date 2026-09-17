cmake_minimum_required(VERSION 3.22.1)
project(duckusb)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(shadowhook REQUIRED CONFIG)

add_library(duckusb SHARED native_hooks.cpp)

target_compile_options(duckusb PRIVATE -Wall -Wextra -fvisibility=hidden -ffunction-sections -fdata-sections)
target_link_options(duckusb PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

target_link_libraries(duckusb log shadowhook::shadowhook)
