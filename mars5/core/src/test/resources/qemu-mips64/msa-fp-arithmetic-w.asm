# Include an inexact quotient and an invalid zero times infinity.
lui $8, 0x4040
sw $8, 0($4)
sw $0, 4($4)
lui $8, 0x8000
sw $8, 8($4)
lui $8, 0x4020
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x40e0
sw $8, 0($4)
lui $8, 0x7f80
sw $8, 4($4)
lui $8, 0x4000
sw $8, 8($4)
lui $8, 0xc0c0
sw $8, 12($4)
ld.w $w1, 0($4)
ctcmsa $1, $0
fadd.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fsub.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
fmul.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
fdiv.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 124($5)
