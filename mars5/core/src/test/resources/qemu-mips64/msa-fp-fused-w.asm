# The product rounds to one when computed separately. Fused cancellation
# exposes the low product bits in both signs.
lui $8, 0x3f80
ori $8, $8, 0x0001
sw $8, 0($4)
lui $8, 0x3f80
ori $8, $8, 0x0001
sw $8, 4($4)
lui $8, 0x3f80
ori $8, $8, 0x0001
sw $8, 8($4)
lui $8, 0x3f80
ori $8, $8, 0x0001
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x3f7f
ori $8, $8, 0xfffe
sw $8, 0($4)
lui $8, 0x3f7f
ori $8, $8, 0xfffe
sw $8, 4($4)
lui $8, 0x3f7f
ori $8, $8, 0xfffe
sw $8, 8($4)
lui $8, 0x3f7f
ori $8, $8, 0xfffe
sw $8, 12($4)
ld.w $w1, 0($4)
lui $8, 0xbf80
sw $8, 0($4)
lui $8, 0x3f80
sw $8, 4($4)
lui $8, 0xbf80
sw $8, 8($4)
lui $8, 0x3f80
sw $8, 12($4)
ld.w $w3, 0($4)
ctcmsa $1, $0
move.v $w2, $w3
fmadd.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
move.v $w2, $w3
fmsub.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
