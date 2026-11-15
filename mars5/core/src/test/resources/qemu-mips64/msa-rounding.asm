# Compare directed rounding and the cause and sticky status fields.
lui $8, 0x3fc0
sw $8, 0($4)
lui $8, 0xbfc0
sw $8, 4($4)
ori $8, $0, 1
sw $8, 8($4)
lui $8, 0x8000
ori $8, $8, 1
sw $8, 12($4)
ld.w $w0, 0($4)
ori $8, $0, 2
ctcmsa $1, $8
frint.w $w1, $w0
cfcmsa $9, $1
ftint_s.w $w2, $w0
cfcmsa $10, $1
ftrunc_s.w $w3, $w0
cfcmsa $11, $1
st.w $w1, 0($5)
st.w $w2, 16($5)
st.w $w3, 32($5)
sw $9, 48($5)
sw $10, 52($5)
sw $11, 56($5)
