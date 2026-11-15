# Pack and interleave distinct vectors. Exercise destination/source aliasing.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
pckev.b $w2, $w0, $w1
pckod.b $w3, $w0, $w1
ilvl.b $w4, $w0, $w1
ilvr.b $w5, $w0, $w1
ilvev.b $w6, $w0, $w1
ilvod.b $w0, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)

# Select the last lane of ws and the first lane of wt.
ld.b $w0, 0($4)
ldi.b $w2, 31
insert.b $w2[1], $0
vshf.b $w2, $w0, $w1
st.b $w2, 96($5)

# Each of bits 6 and 7 independently forces its result lane to zero.
ldi.b $w2, 64
ori $8, $0, 128
insert.b $w2[1], $8
vshf.b $w2, $w0, $w1
st.b $w2, 112($5)

# Lane zero is replaced from wt. Later lanes read its original value.
ldi.b $w2, 16
ori $8, $0, 1
insert.b $w2[0], $8
vshf.b $w2, $w2, $w1
st.b $w2, 128($5)
