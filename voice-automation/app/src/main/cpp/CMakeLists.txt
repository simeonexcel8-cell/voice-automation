cmake_minimum_required(VERSION 3.22.1)
# CXX is enabled so the final link pulls in the C++ runtime the engine may need
# (the STL is set to c++_static in app/build.gradle.kts).
project(needle_bridge C CXX)

# The Cactus engine for Android comes from the "android-arm64" platform folder:
#   pip install cactus-needle
#   needle build --platform android-arm64 --out ./needle-android
# Copy libneedle.a and needle.h from that folder into:
#   app/src/main/cpp/cactus/<ABI>/libneedle.a
#   app/src/main/cpp/cactus/include/needle.h
set(CACTUS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/cactus)

if(NOT EXISTS ${CACTUS_DIR}/${ANDROID_ABI}/libneedle.a)
    message(FATAL_ERROR
        "Missing ${CACTUS_DIR}/${ANDROID_ABI}/libneedle.a — see README.md, step 2.")
endif()

add_library(needle STATIC IMPORTED)
set_target_properties(needle PROPERTIES
    IMPORTED_LOCATION ${CACTUS_DIR}/${ANDROID_ABI}/libneedle.a)

add_library(needle_bridge SHARED needle_jni.c)
target_include_directories(needle_bridge PRIVATE ${CACTUS_DIR}/include)

set_target_properties(needle_bridge PROPERTIES LINKER_LANGUAGE CXX)
target_link_libraries(needle_bridge PRIVATE needle log m)
