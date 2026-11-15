# Insertion can replace one outermost bit, three bits, or a whole lane.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ldi.d $w2, -1
insert.d $w2[1], $0
move.v $w3, $w1
move.v $w4, $w1
move.v $w5, $w1
move.v $w6, $w1
binsl.d $w3, $w0, $w2
binsr.d $w4, $w0, $w2
binsli.d $w5, $w0, 2
binsri.d $w6, $w0, 2
st.b $w3, 0($5)
st.b $w4, 16($5)
st.b $w5, 32($5)
st.b $w6, 48($5)

# Also exercise the immediate endpoints and destination/count aliasing.
move.v $w3, $w1
binsli.d $w3, $w0, 63
binsri.d $w1, $w0, 0
binsr.d $w2, $w0, $w2
st.b $w3, 64($5)
st.b $w1, 80($5)
st.b $w2, 96($5)
