cmake_minimum_required(VERSION 3.22.1)

project(wdsp_native)

# Just the system-property wait JNI shim - see PropertyWatch.java/property_wait.cpp. Both
# __system_property_wait and __system_property_area_serial are stable, public NDK API
# (sys/system_properties.h since API 26), not a private/hidden bionic symbol.
add_library(wdsp_native SHARED
        property_wait.cpp)

target_compile_options(wdsp_native PRIVATE
        -O2
        -Wall
        -Wextra)

find_library(log-lib log)
target_link_libraries(wdsp_native ${log-lib})
