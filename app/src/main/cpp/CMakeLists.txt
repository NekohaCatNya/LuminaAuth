cmake_minimum_required(VERSION 3.22)
project(lumina-native)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

add_library(lumina-native SHARED
    native_bridge.cpp
    native_http.cpp
    plugin_engine.cpp
    log_ring_buffer.cpp
    fast_json.cpp
)

find_library(log-lib log)
find_library(z-lib z)

target_link_libraries(lumina-native
    ${log-lib}
    ${z-lib}
)

target_compile_options(lumina-native PRIVATE
    -O2
    -Wall
)
