# The vector indices choose the first bit and wrap to the last bit.
ld.b $w0, 0($4)
ldi.h $w1, -1
insert.h $w1[1], $0
bclr.h $w2, $w0, $w1
bset.h $w3, $w0, $w1
bneg.h $w4, $w0, $w1
bclri.h $w5, $w0, 15
bseti.h $w6, $w0, 0
bnegi.h $w0, $w0, 15
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)
