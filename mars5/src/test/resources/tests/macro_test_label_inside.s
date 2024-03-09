.macro with_label (%thing)
truth:
  li $v0, 1
  li $a0, 1
  syscall

falsehood:
  li $v0, 1
  li $a0, 0
  syscall

other:
  li $v0, 1
  li $a0, -1
  syscall

main:
  li $t1, 1
  bgtz $t1, truth
.end_macro