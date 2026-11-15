# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.24)
project(croonvox_spike_audio LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
FetchContent_Declare(
        signalsmith-stretch
        GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
        GIT_TAG 1.4.0
        GIT_SHALLOW ON
)
FetchContent_MakeAvailable(signalsmith-stretch)

find_package(oboe REQUIRED CONFIG)

add_library(spike_audio SHARED
        SpikeEngine.cpp
        WavWriter.cpp
        jni_bridge.cpp
)

target_link_libraries(spike_audio PRIVATE oboe::oboe signalsmith-stretch log android)

# Signalsmith Stretch is ~10x slower unoptimised; keep DSP optimised even in debug builds.
target_compile_options(spike_audio PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
