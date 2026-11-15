# The vector indices choose the first bit and wrap to the last bit.
ld.b $w0, 0($4)
ldi.b $w1, -1
insert.b $w1[1], $0
bclr.b $w2, $w0, $w1
bset.b $w3, $w0, $w1
bneg.b $w4, $w0, $w1
bclri.b $w5, $w0, 7
bseti.b $w6, $w0, 0
bnegi.b $w0, $w0, 7
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)
