.data
result: .word 0
.text
double:
    addu $v0, $a0, $a0
    sw   $v0, result
done:
    li   $v0, 10
    syscall
