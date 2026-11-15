# Insertion can replace one outermost bit, three bits, or a whole lane.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
ldi.b $w2, -1
insert.b $w2[1], $0
move.v $w3, $w1
move.v $w4, $w1
move.v $w5, $w1
move.v $w6, $w1
binsl.b $w3, $w0, $w2
binsr.b $w4, $w0, $w2
binsli.b $w5, $w0, 2
binsri.b $w6, $w0, 2
st.b $w3, 0($5)
st.b $w4, 16($5)
st.b $w5, 32($5)
st.b $w6, 48($5)

# Also exercise the immediate endpoints and destination/count aliasing.
move.v $w3, $w1
binsli.b $w3, $w0, 7
binsri.b $w1, $w0, 0
binsr.b $w2, $w0, $w2
st.b $w3, 64($5)
st.b $w1, 80($5)
st.b $w2, 96($5)
