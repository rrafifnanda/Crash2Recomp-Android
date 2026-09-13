cmake_minimum_required(VERSION 3.20)
project(crash2_android_probe C CXX)

add_library(crash2-probe-jni SHARED probe_jni.c)
target_link_libraries(crash2-probe-jni PRIVATE dl log)

add_library(crash2-probe-payload SHARED probe_tool.c)

add_library(builder-jni SHARED builder_jni.cpp)
target_compile_features(builder-jni PRIVATE cxx_std_17)
target_link_libraries(builder-jni PRIVATE log)
