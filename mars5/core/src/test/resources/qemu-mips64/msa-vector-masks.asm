# Use a destination with distinct bits for each merge and select.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ld.b $w2, 32($4)
move.v $w3, $w2
move.v $w4, $w2
move.v $w5, $w2
move.v $w6, $w2
move.v $w7, $w2
move.v $w8, $w2
bmnz.v $w3, $w0, $w1
bmz.v $w4, $w0, $w1
bsel.v $w5, $w0, $w1
bmnzi.b $w6, $w0, 0x81
bmzi.b $w7, $w0, 0x81
bseli.b $w8, $w0, 0xa5
st.b $w3, 0($5)
st.b $w4, 16($5)
st.b $w5, 32($5)
st.b $w6, 48($5)
st.b $w7, 64($5)
st.b $w8, 80($5)

# Exercise a destination that also supplies the mask.
bmnz.v $w1, $w0, $w1
st.b $w1, 96($5)
