# Smallest single subnormal, negative zero, signaling NaN, and -1.5.
lui $8, 0x0000
ori $8, $8, 0x0001
sw $8, 0($4)
lui $8, 0x8000
sw $8, 4($4)
lui $8, 0x7f80
ori $8, $8, 0x0001
sw $8, 8($4)
lui $8, 0xbfc0
sw $8, 12($4)
ld.w $w0, 0($4)
ctcmsa $1, $0
fexupl.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fexupr.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
ffql.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
ffqr.d $w2, $w0
cfcmsa $9, $1
st.d $w2, 48($5)
sw $9, 124($5)
