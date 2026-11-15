# Record the fault state in the output buffer. Normalize addresses across layouts.
mfc0 $8, $13
lui $9, 0x8000
ori $9, $9, 0x007c
and $9, $8, $9
sw $9, 0($5)
dmfc0 $9, $14
dsubu $9, $9, $6
sd $9, 8($5)
mfc0 $9, $12
andi $9, $9, 2
sw $9, 16($5)
sd $12, 24($5)
andi $8, $8, 0x007c
ori $9, $0, 56
beq $8, $9, record_msa
nop
ori $9, $0, 16
beq $8, $9, record_address
nop
ori $9, $0, 20
bne $8, $9, done
nop
record_address:
dmfc0 $9, $8
dsubu $9, $9, $4
sd $9, 32($5)
dmfc0 $9, $14
dmfc0 $10, $8
bne $9, $10, done
nop
# An instruction fetch fault also exposes the link that preceded the fetch.
dsubu $9, $31, $6
sd $9, 64($5)
beq $0, $0, done
nop
record_msa:
cfcmsa $9, $1
sw $9, 40($5)
st.w $w2, 48($5)
