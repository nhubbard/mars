# Use powers of two for the reciprocal results.
# Approximate reciprocals may signal Inexact even on these inputs.
ori $8, $0, 0x4010
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x3fd0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $10, $0, 0x1004
nor $10, $10, $0
ctcmsa $1, $0
fsqrt.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
frsqrt.d $w2, $w0
cfcmsa $9, $1
and $9, $9, $10
st.d $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
frcp.d $w2, $w0
cfcmsa $9, $1
and $9, $9, $10
st.d $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
flog2.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 48($5)
sw $9, 124($5)
