# Compare delayed and compact calls, including an explicit link register.
daddiu $12, $0, 0
addiupc $13, 4
jal call_0
daddiu $12, $12, 1
dsubu $15, $31, $13
sd $15, 0($5)
bc continue_0
nop
call_0:
daddiu $12, $12, 2
jr $31
daddiu $12, $12, 4
continue_0:
sd $12, 64($5)
addiupc $13, 4
bal call_1
daddiu $12, $12, 1
dsubu $15, $31, $13
sd $15, 8($5)
bc continue_1
nop
call_1:
daddiu $12, $12, 2
jr.hb $31
daddiu $12, $12, 4
continue_1:
sd $12, 72($5)
addiupc $8, 32
addiupc $13, 4
jalr $8
daddiu $12, $12, 1
dsubu $15, $31, $13
sd $15, 16($5)
bc continue_2
nop
call_2:
daddiu $12, $12, 2
jr $31
daddiu $12, $12, 4
continue_2:
sd $12, 80($5)
addiupc $8, 32
addiupc $13, 4
jalr $14, $8
daddiu $12, $12, 1
dsubu $15, $14, $13
sd $15, 24($5)
bc continue_3
nop
call_3:
daddiu $12, $12, 2
jr.hb $14
daddiu $12, $12, 4
continue_3:
sd $12, 88($5)
addiupc $8, 32
addiupc $13, 4
jalr.hb $8
daddiu $12, $12, 1
dsubu $15, $31, $13
sd $15, 32($5)
bc continue_4
nop
call_4:
daddiu $12, $12, 2
jr.hb $31
daddiu $12, $12, 4
continue_4:
sd $12, 96($5)
addiupc $8, 32
addiupc $13, 4
jalr.hb $14, $8
daddiu $12, $12, 1
dsubu $15, $14, $13
sd $15, 40($5)
bc continue_5
nop
call_5:
daddiu $12, $12, 2
jr $14
daddiu $12, $12, 4
continue_5:
sd $12, 104($5)
addiupc $31, 36
addiupc $13, 4
jialc $31, -4
daddiu $12, $12, 1
dsubu $15, $31, $13
sd $15, 48($5)
bc continue_6
nop
call_6:
daddiu $12, $12, 2
jic $31, 0
daddiu $12, $12, 4
continue_6:
sd $12, 112($5)
# J and NAL execute their delay slots. NAL links and falls through.
j delayed_target
daddiu $12, $12, 8
daddiu $12, $12, 16
delayed_target:
addiupc $13, 4
nal
daddiu $12, $12, 32
dsubu $15, $31, $13
sd $15, 56($5)
sd $12, 120($5)
