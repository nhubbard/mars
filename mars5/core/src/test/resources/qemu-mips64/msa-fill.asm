# FILL takes the low lane-width bits from a GPR with distinct bytes.
ld $8, 0($4)
fill.b $w0, $8
fill.h $w1, $8
fill.w $w2, $8
fill.d $w3, $8
st.b $w0, 0($5)
st.b $w1, 16($5)
st.b $w2, 32($5)
st.b $w3, 48($5)

# LDI sign-extends its ten-bit immediate before filling wider lanes.
ldi.b $w0, -512
ldi.h $w1, -512
ldi.w $w2, -512
ldi.d $w3, -512
st.b $w0, 64($5)
st.b $w1, 80($5)
st.b $w2, 96($5)
st.b $w3, 112($5)
