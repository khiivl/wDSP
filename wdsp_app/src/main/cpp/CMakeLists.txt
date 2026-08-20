cmake_minimum_required(VERSION 3.22.1)

project(wdsp_native)

# test_analyzer.cpp and test_sweep.cpp are deliberately absent: they are host harnesses with
# their own main(), built with plain g++ on a desktop, not part of the library.
add_library(wdsp_native SHARED
        wdsp_jni.cpp
        fft.cpp
        stitcher.cpp
        analyzer.cpp
        sweep.cpp)

target_compile_options(wdsp_native PRIVATE
        -O3
        -ffast-math
        -fno-exceptions
        -fno-rtti
        -Wall
        -Wextra)

find_library(log-lib log)
target_link_libraries(wdsp_native ${log-lib})
