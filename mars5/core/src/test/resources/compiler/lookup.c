typedef unsigned long long u64;
static const u64 table[] = { 0x0123456789abcdefULL, 0xfedcba9876543210ULL };
u64 lookup(unsigned i) {
    return table[i & 1] + 1;
}
