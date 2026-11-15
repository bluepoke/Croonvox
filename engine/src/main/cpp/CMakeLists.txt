# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.24)
project(croonvox_engine LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Signalsmith Stretch: real-time Transpose (ADR 5).
include(FetchContent)
FetchContent_Declare(
        signalsmith-stretch
        GIT_REPOSITORY https://github.com/Signalsmith-Audio/signalsmith-stretch.git
        GIT_TAG 1.4.0
        GIT_SHALLOW ON
)
FetchContent_MakeAvailable(signalsmith-stretch)

# Oboe via prefab: low-latency full-duplex audio (ADR 4).
find_package(oboe REQUIRED CONFIG)

add_library(croonvox_engine SHARED
        PlaybackEngine.cpp
        jni_bridge.cpp
)

target_link_libraries(croonvox_engine PRIVATE oboe::oboe signalsmith-stretch log android)

# Signalsmith Stretch is ~10x slower unoptimised; keep DSP optimised even in debug builds.
target_compile_options(croonvox_engine PRIVATE -O2 -Wall -Wextra -Wno-unused-parameter)
