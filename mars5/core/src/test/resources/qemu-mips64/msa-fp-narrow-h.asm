# Pack both source vectors. Round halfway and tiny interchange values upward.
lui $8, 0x3f80
ori $8, $8, 0x1000
sw $8, 0($4)
lui $8, 0x3380
sw $8, 4($4)
lui $8, 0x8000
sw $8, 8($4)
lui $8, 0x7f80
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0xbf80
ori $8, $8, 0x1000
sw $8, 0($4)
lui $8, 0xb300
sw $8, 4($4)
lui $8, 0xbf80
sw $8, 8($4)
lui $8, 0x4000
sw $8, 12($4)
ld.w $w1, 0($4)
ori $10, $0, 2
ctcmsa $1, $10
fexdo.h $w2, $w0, $w1
cfcmsa $9, $1
st.h $w2, 0($5)
sw $9, 112($5)
