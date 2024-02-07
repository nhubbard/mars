.macro unfull_match
  li $v0, 1
  li $a0, 1
  syscall
.end_macro

.macro full_match
  li $v0, 1
  li $a0, 2
  syscall
.end_macro