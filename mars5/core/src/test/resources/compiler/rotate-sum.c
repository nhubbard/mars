typedef unsigned long long u64;

u64 rotate_sum(const u64 *words, unsigned count) {
    u64 accumulator = 0;
    for (unsigned i = 0; i < count; i++) {
        u64 value = words[i];
        accumulator += (value << 7) | (value >> 57);
    }
    return accumulator;
}
