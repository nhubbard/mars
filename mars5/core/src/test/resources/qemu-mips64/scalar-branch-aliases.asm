# These source aliases reverse the canonical less-than/greater-or-equal operands.
daddiu $8, $0, -1
ori $9, $0, 1
or $10, $0, $0
bgtc $9, $8, signed_greater # llvm-word 0x5d090001
daddiu $10, $10, 1
signed_greater:
blec $9, $8, signed_less_equal # llvm-word 0x59090001
daddiu $10, $10, 2
signed_less_equal:
bgtuc $9, $8, unsigned_greater # llvm-word 0x1d090001
daddiu $10, $10, 4
unsigned_greater:
bleuc $9, $8, unsigned_less_equal # llvm-word 0x19090001
daddiu $10, $10, 8
unsigned_less_equal:
b alias_done
daddiu $10, $10, 16
daddiu $10, $10, 32
alias_done:
sd $10, 0($5)
