# Pack and interleave distinct vectors. Exercise destination/source aliasing.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
pckev.d $w2, $w0, $w1
pckod.d $w3, $w0, $w1
ilvl.d $w4, $w0, $w1
ilvr.d $w5, $w0, $w1
ilvev.d $w6, $w0, $w1
ilvod.d $w0, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)

# Select the last lane of ws and the first lane of wt.
ld.b $w0, 0($4)
ldi.d $w2, 3
insert.d $w2[1], $0
vshf.d $w2, $w0, $w1
st.b $w2, 96($5)

# Each of bits 6 and 7 independently forces its result lane to zero.
ldi.d $w2, 64
ori $8, $0, 128
insert.d $w2[1], $8
vshf.d $w2, $w0, $w1
st.b $w2, 112($5)

# Lane zero is replaced from wt. Later lanes read its original value.
ldi.d $w2, 2
ori $8, $0, 1
insert.d $w2[0], $8
vshf.d $w2, $w2, $w1
st.b $w2, 128($5)
