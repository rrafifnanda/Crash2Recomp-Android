#ifdef PROBE_EXECUTABLE
#include <stdio.h>

int main(void) {
    puts("probe-ok");
    return 0;
}
#else
__attribute__((visibility("default"))) int crash2_probe_value(void) {
    return 94154;
}
#endif
