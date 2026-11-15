# The vector indices choose the first bit and wrap to the last bit.
ld.b $w0, 0($4)
ldi.w $w1, -1
insert.w $w1[1], $0
bclr.w $w2, $w0, $w1
bset.w $w3, $w0, $w1
bneg.w $w4, $w0, $w1
bclri.w $w5, $w0, 31
bseti.w $w6, $w0, 0
bnegi.w $w0, $w0, 31
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)
