# Test both sum overflow and a non-sign-extended input operand.
lui $8, 0x7fff
ori $8, $8, 0xffff
ori $9, $0, 1
dsll32 $10, $9, 0
daddiu $11, $0, 0
bovc $8, $9, overflow_0_done
daddiu $11, $11, 1
overflow_0_done:
dsll $11, $11, 1
bovc $9, $9, overflow_1_done
daddiu $11, $11, 1
overflow_1_done:
dsll $11, $11, 1
bovc $10, $0, overflow_2_done
daddiu $11, $11, 1
overflow_2_done:
dsll $11, $11, 1
bnvc $8, $9, overflow_3_done
daddiu $11, $11, 1
overflow_3_done:
dsll $11, $11, 1
bnvc $9, $9, overflow_4_done
daddiu $11, $11, 1
overflow_4_done:
dsll $11, $11, 1
bnvc $10, $0, overflow_5_done
daddiu $11, $11, 1
overflow_5_done:
dsll $11, $11, 1
sd $11, 0($5)
