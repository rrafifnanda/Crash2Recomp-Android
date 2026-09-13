cmake_minimum_required(VERSION 3.20)
project(crash2_android_runtime_bundle C CXX)

set(CMAKE_C_STANDARD 99)
set(CMAKE_CXX_STANDARD 17)
set(PSXRECOMP_ROOT "" CACHE PATH "Path to the pinned Android psxrecomp tree")
if(NOT EXISTS "${PSXRECOMP_ROOT}/runtime/runtime.cmake")
    message(FATAL_ERROR "PSXRECOMP_ROOT does not contain runtime/runtime.cmake")
endif()

set(PSX_RECOMP_UI OFF CACHE BOOL "" FORCE)
set(PSX_ENABLE_VULKAN OFF CACHE BOOL "" FORCE)
set(PSX_REWIND OFF CACHE BOOL "" FORCE)
set(PSXRECOMP_BIOS_STEMS OpenBIOS CACHE STRING "" FORCE)
include("${PSXRECOMP_ROOT}/runtime/runtime.cmake")

psxrecomp_add_runtime_target(psx-runtime
    GAME_GENERATED_FULL_C "${CMAKE_CURRENT_SOURCE_DIR}/android_game_stub.c"
    GAME_GENERATED_DISPATCH_C "${CMAKE_CURRENT_SOURCE_DIR}/android_dispatch_stub.c"
    EXTRAS_SOURCES
        "${CMAKE_CURRENT_SOURCE_DIR}/game_module_bridge.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/android_host_hooks.c"
        "${CMAKE_CURRENT_SOURCE_DIR}/android_input_jni.c"
    WINDOW_TITLE "Crash Bandicoot 2 Recompiled"
    DEFAULT_GAME_CONFIG_PATH "game.toml"
)

# This build produces the immutable Android runtime objects and dependency
# archives. The real game dispatch is generated and linked on the user's phone.
target_link_options(psx-runtime PRIVATE "-Wl,--unresolved-symbols=ignore-all")
