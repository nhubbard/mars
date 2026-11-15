# Large integer inputs require rounding, with different signed interpretations.
lui $8, 0xffff
ori $8, $8, 0xffff
sw $8, 0($4)
lui $8, 0x0100
ori $8, $8, 0x0001
sw $8, 4($4)
lui $8, 0x8000
sw $8, 8($4)
lui $8, 0x0000
ori $8, $8, 0x0001
sw $8, 12($4)
ld.w $w0, 0($4)
ori $10, $0, 2
ctcmsa $1, $10
ffint_s.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $10
ffint_u.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
