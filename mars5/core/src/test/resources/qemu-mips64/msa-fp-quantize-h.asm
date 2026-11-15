# Convert signed values around the Q-format limits, including a NaN.
lui $8, 0x3f80
sw $8, 0($4)
lui $8, 0xbf80
sw $8, 4($4)
lui $8, 0x3f00
sw $8, 8($4)
lui $8, 0xbf00
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x7fc0
ori $8, $8, 0x0011
sw $8, 0($4)
lui $8, 0xc000
sw $8, 4($4)
lui $8, 0x3780
sw $8, 8($4)
lui $8, 0xb780
sw $8, 12($4)
ld.w $w1, 0($4)
ctcmsa $1, $0
ftq.h $w2, $w0, $w1
cfcmsa $9, $1
st.h $w2, 0($5)
sw $9, 112($5)
