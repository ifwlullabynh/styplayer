cmake_minimum_required(VERSION 3.22.1)
project("sxstyleplayer")

# ─── FluidSynth JNI Bridge ───────────────────────────────────────────────────
# This builds the JNI bridge between Kotlin and FluidSynth
# FluidSynth prebuilt .so must be placed in:
#   app/src/main/jniLibs/arm64-v8a/libfluidsynth.so  (and other ABIs)
# SF2 SoundFont should be placed in:
#   app/src/main/assets/yamaha_xg.sf2

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Path to FluidSynth prebuilt headers
set(FLUIDSYNTH_INCLUDE_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/include")
set(FLUIDSYNTH_LIB_DIR "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# Check if FluidSynth is available
if(EXISTS "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so")
    message(STATUS "FluidSynth found for ${ANDROID_ABI}")
    set(HAVE_FLUIDSYNTH TRUE)
else()
    message(WARNING "FluidSynth not found for ${ANDROID_ABI} - using stub implementation")
    set(HAVE_FLUIDSYNTH FALSE)
endif()

# Main JNI bridge library
add_library(
    fluidsynth_bridge
    SHARED
    fluidsynth_bridge.cpp
)

# Compile definitions
if(HAVE_FLUIDSYNTH)
    target_compile_definitions(fluidsynth_bridge PRIVATE HAVE_FLUIDSYNTH=1)
    target_include_directories(fluidsynth_bridge PRIVATE ${FLUIDSYNTH_INCLUDE_DIR})
endif()

# Android system libraries
find_library(log-lib log)
find_library(android-lib android)

# Link libraries
target_link_libraries(
    fluidsynth_bridge
    ${log-lib}
    ${android-lib}
)

# Link FluidSynth if available
if(HAVE_FLUIDSYNTH)
    add_library(fluidsynth SHARED IMPORTED)
    set_target_properties(fluidsynth PROPERTIES
        IMPORTED_LOCATION "${FLUIDSYNTH_LIB_DIR}/libfluidsynth.so"
    )
    target_link_libraries(fluidsynth_bridge fluidsynth)
endif()

# Compiler optimizations for audio processing
target_compile_options(fluidsynth_bridge PRIVATE
    -O2
    -ffast-math
    -Wall
)
