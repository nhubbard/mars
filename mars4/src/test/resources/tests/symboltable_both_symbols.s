.data
    data1: .word 10
    data2: .word 20

.text
main:
  j do_something

do_something:
  li $v0, 1
  li $a0, 1
  syscall

  li $v0, 10
  syscall