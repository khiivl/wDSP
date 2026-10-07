cmake_minimum_required(VERSION 3.22.1)

project(wdsp_native)

# test_analyzer.cpp, test_sweep.cpp and test_miccal.cpp are deliberately absent: they are host
# harnesses with their own main(), built with plain g++ on a desktop, not part of the library.
#   g++ -O2 -std=c++17 -o /tmp/miccal test_miccal.cpp sweep.cpp analyzer.cpp fft.cpp stitcher.cpp
# test_miccal replays the owner's own 20.09 measurement through the microphone estimate and the
# synthesis: it first reproduces, band for band, the preset that unit printed from the curve it
# had stored, so any difference below that line is the code changing and not the harness.
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
target_link_libraries(wdsp_native ${log-lib} dl)
