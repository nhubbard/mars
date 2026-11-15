# Compare both halves of ordinary logic and byte-replicated immediate logic.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
and.v $w2, $w0, $w1
or.v $w3, $w0, $w1
nor.v $w4, $w0, $w1
xor.v $w1, $w0, $w1
andi.b $w5, $w0, 0xf0
ori.b $w6, $w0, 0x81
nori.b $w7, $w0, 0x55
xori.b $w0, $w0, 0xa5
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w1, 48($5)
st.b $w5, 64($5)
st.b $w6, 80($5)
st.b $w7, 96($5)
st.b $w0, 112($5)
