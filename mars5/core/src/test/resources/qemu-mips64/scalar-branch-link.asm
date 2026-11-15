# Compact conditional calls write PC+4 on both outcomes.
daddiu $8, $0, -1
ori $9, $0, 1
dsll32 $9, $9, 0
ori $10, $0, 0
daddiu $12, $0, 0

ori $31, $0, 1
addiupc $13, 4
bltzalc $8, call_0
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 0($5)
bc continue_0
nop
call_0:
daddiu $12, $12, 2
jic $31, 0
nop
continue_0:

ori $31, $0, 1
addiupc $13, 4
bltzalc $9, call_1
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 8($5)
bc continue_1
nop
call_1:
daddiu $12, $12, 2
jic $31, 0
nop
continue_1:

ori $31, $0, 1
addiupc $13, 4
blezalc $10, call_2
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 16($5)
bc continue_2
nop
call_2:
daddiu $12, $12, 2
jic $31, 0
nop
continue_2:

ori $31, $0, 1
addiupc $13, 4
blezalc $9, call_3
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 24($5)
bc continue_3
nop
call_3:
daddiu $12, $12, 2
jic $31, 0
nop
continue_3:

ori $31, $0, 1
addiupc $13, 4
bgezalc $10, call_4
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 32($5)
bc continue_4
nop
call_4:
daddiu $12, $12, 2
jic $31, 0
nop
continue_4:

ori $31, $0, 1
addiupc $13, 4
bgezalc $8, call_5
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 40($5)
bc continue_5
nop
call_5:
daddiu $12, $12, 2
jic $31, 0
nop
continue_5:

ori $31, $0, 1
addiupc $13, 4
bgtzalc $9, call_6
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 48($5)
bc continue_6
nop
call_6:
daddiu $12, $12, 2
jic $31, 0
nop
continue_6:

ori $31, $0, 1
addiupc $13, 4
bgtzalc $10, call_7
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 56($5)
bc continue_7
nop
call_7:
daddiu $12, $12, 2
jic $31, 0
nop
continue_7:

ori $31, $0, 1
addiupc $13, 4
beqzalc $10, call_8
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 64($5)
bc continue_8
nop
call_8:
daddiu $12, $12, 2
jic $31, 0
nop
continue_8:

ori $31, $0, 1
addiupc $13, 4
beqzalc $9, call_9
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 72($5)
bc continue_9
nop
call_9:
daddiu $12, $12, 2
jic $31, 0
nop
continue_9:

ori $31, $0, 1
addiupc $13, 4
bnezalc $9, call_10
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 80($5)
bc continue_10
nop
call_10:
daddiu $12, $12, 2
jic $31, 0
nop
continue_10:

ori $31, $0, 1
addiupc $13, 4
bnezalc $10, call_11
daddiu $12, $12, 1
dsubu $14, $31, $13
sd $14, 88($5)
bc continue_11
nop
call_11:
daddiu $12, $12, 2
jic $31, 0
nop
continue_11:
sd $12, 96($5)
