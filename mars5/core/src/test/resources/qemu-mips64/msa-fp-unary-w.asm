# Use powers of two for the reciprocal results.
# Approximate reciprocals may signal Inexact even on these inputs.
lui $8, 0x4080
sw $8, 0($4)
lui $8, 0x4180
sw $8, 4($4)
lui $8, 0x3e80
sw $8, 8($4)
lui $8, 0x3f80
sw $8, 12($4)
ld.w $w0, 0($4)
ori $10, $0, 0x1004
nor $10, $10, $0
ctcmsa $1, $0
fsqrt.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
frsqrt.w $w2, $w0
cfcmsa $9, $1
and $9, $9, $10
st.w $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
frcp.w $w2, $w0
cfcmsa $9, $1
and $9, $9, $10
st.w $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
flog2.w $w2, $w0
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 124($5)
