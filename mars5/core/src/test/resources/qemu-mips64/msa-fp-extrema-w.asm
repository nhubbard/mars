# Compare extrema where numeric values tie in magnitude.
lui $8, 0xc000
sw $8, 0($4)
lui $8, 0x8000
sw $8, 4($4)
lui $8, 0x7fc0
ori $8, $8, 0x0011
sw $8, 8($4)
lui $8, 0x7f80
ori $8, $8, 0x0022
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x4000
sw $8, 0($4)
sw $0, 4($4)
lui $8, 0x4000
sw $8, 8($4)
lui $8, 0x4000
sw $8, 12($4)
ld.w $w1, 0($4)
ctcmsa $1, $0
fmin.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fmax.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
fmin_a.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
fmax_a.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 124($5)
