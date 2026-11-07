cmake_minimum_required(VERSION 3.22)
project(lumina-native)

add_library(lumina-native SHARED
    native_bridge.cpp
    native_http.cpp
    log_ring_buffer.cpp
    fast_json.cpp
)

find_library(log-lib log)

target_link_libraries(lumina-native
    ${log-lib}
)

target_compile_options(lumina-native PRIVATE
    -O2
    -Wall
)
