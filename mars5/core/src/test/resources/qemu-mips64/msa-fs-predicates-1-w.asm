# Compare quiet NaN and ordered lanes of opposite signs.
lui $8, 0x7fc0
ori $8, $8, 0x0011
sw $8, 0($4)
lui $8, 0xbf80
sw $8, 4($4)
sw $0, 8($4)
lui $8, 0x4040
sw $8, 12($4)
ld.w $w0, 0($4)
lui $8, 0x3f80
sw $8, 0($4)
lui $8, 0x3f80
sw $8, 4($4)
lui $8, 0x8000
sw $8, 8($4)
lui $8, 0x4000
sw $8, 12($4)
ld.w $w1, 0($4)
ctcmsa $1, $0
fsaf.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fsun.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
fseq.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
fsueq.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 48($5)
sw $9, 124($5)
ctcmsa $1, $0
fslt.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 64($5)
sw $9, 128($5)
ctcmsa $1, $0
fsult.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 80($5)
sw $9, 132($5)
