# Use opposite signs and a positive value whose low word is zero.
daddiu $8, $0, -1
ori $9, $0, 1
dsll32 $9, $9, 0
ori $10, $0, 0
daddiu $11, $0, 0

# Test both outcomes, keeping the slot distinguishable from fall-through.
bltzc $8, branch_0_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_0_done:
dsll $11, $11, 2
bltzc $9, branch_1_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_1_done:
dsll $11, $11, 2
blezc $10, branch_2_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_2_done:
dsll $11, $11, 2
blezc $9, branch_3_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_3_done:
dsll $11, $11, 2
bgezc $10, branch_4_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_4_done:
dsll $11, $11, 2
bgezc $8, branch_5_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_5_done:
dsll $11, $11, 2
bgtzc $9, branch_6_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_6_done:
dsll $11, $11, 2
bgtzc $10, branch_7_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_7_done:
dsll $11, $11, 2
beqzc $10, branch_8_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_8_done:
dsll $11, $11, 2
beqzc $9, branch_9_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_9_done:
dsll $11, $11, 2
bnezc $9, branch_10_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_10_done:
dsll $11, $11, 2
bnezc $10, branch_11_done
daddiu $11, $11, 2
daddiu $11, $11, 1
branch_11_done:
dsll $11, $11, 2
sd $11, 0($5)
