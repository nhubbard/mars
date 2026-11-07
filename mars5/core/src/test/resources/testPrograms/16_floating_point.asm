# Exercise raw transfers, single arithmetic, double conversion, and a condition flag.
.text
.globl main
main:
    li $t0, 0x3fc00000       # 1.5f
    mtc1 $t0, $f0
    li $t0, 0x40200000       # 2.5f
    mtc1 $t0, $f2
    add.s $f4, $f0, $f2
    cvt.d.s $f6, $f4
    c.lt.s $f0, $f2
    li $v0, 10
    syscall
