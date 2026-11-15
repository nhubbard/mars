# Write float bit patterns in the active byte order before loading their lanes.
sw $0, 0($4)
lui $8, 0x8000
sw $8, 4($4)
sw $8, 16($4)
sw $0, 20($4)
lui $8, 0x7fc0
ori $8, $8, 0x0011
sw $8, 8($4)
lui $8, 0x7f80
ori $8, $8, 0x0022
sw $8, 12($4)
lui $8, 0x3f80
sw $8, 24($4)
lui $8, 0x4000
sw $8, 28($4)
ld.w $w0, 0($4)
ld.w $w1, 16($4)
fclass.w $w2, $w0
fmin.w $w3, $w0, $w1
fmax.w $w4, $w0, $w1
fcun.w $w5, $w0, $w1
st.w $w2, 0($5)
st.w $w3, 16($5)
st.w $w4, 32($5)
st.w $w5, 48($5)
