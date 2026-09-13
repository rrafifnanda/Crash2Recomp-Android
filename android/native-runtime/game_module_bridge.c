#include <dlfcn.h>
#include <pthread.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>

#include "cpu_state.h"

typedef int (*dispatch_fn)(CPUState *, uint32_t);
typedef int (*address_fn)(uint32_t);

static pthread_once_t once = PTHREAD_ONCE_INIT;
static dispatch_fn dispatch_game;
static address_fn address_in_text;
static address_fn is_function_entry;

static void load_game_module(void) {
    const char *path = getenv("PSX_GAME_MODULE");
    if (!path || !*path) {
        fputs("Crash2Recomp: PSX_GAME_MODULE is not set\n", stderr);
        abort();
    }
    void *module = dlopen(path, RTLD_NOW | RTLD_LOCAL);
    if (!module) {
        fprintf(stderr, "Crash2Recomp: cannot load game module: %s\n", dlerror());
        abort();
    }
    dispatch_game = (dispatch_fn)dlsym(module, "psx_dispatch_game_compiled");
    address_in_text = (address_fn)dlsym(module, "psx_game_address_in_text");
    is_function_entry = (address_fn)dlsym(module, "psx_game_is_function_entry");
    if (!dispatch_game || !address_in_text || !is_function_entry) {
        fputs("Crash2Recomp: generated game module has an incompatible interface\n", stderr);
        abort();
    }
}

int psx_dispatch_game_compiled(CPUState *cpu, uint32_t address) {
    pthread_once(&once, load_game_module);
    return dispatch_game(cpu, address);
}

int psx_game_address_in_text(uint32_t address) {
    pthread_once(&once, load_game_module);
    return address_in_text(address);
}

int psx_game_is_function_entry(uint32_t address) {
    pthread_once(&once, load_game_module);
    return is_function_entry(address);
}
