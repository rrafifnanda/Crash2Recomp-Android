cmake_minimum_required(VERSION 3.20)
project(crash2_android_probe C)

add_library(crash2-probe-jni SHARED probe_jni.c)
target_link_libraries(crash2-probe-jni PRIVATE dl log)

add_library(crash2-probe-payload SHARED probe_tool.c)
