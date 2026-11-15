# The vector indices choose the first bit and wrap to the last bit.
ld.b $w0, 0($4)
ldi.d $w1, -1
insert.d $w1[1], $0
bclr.d $w2, $w0, $w1
bset.d $w3, $w0, $w1
bneg.d $w4, $w0, $w1
bclri.d $w5, $w0, 63
bseti.d $w6, $w0, 0
bnegi.d $w0, $w0, 63
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)
