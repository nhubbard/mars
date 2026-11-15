# Pack and interleave distinct vectors. Exercise destination/source aliasing.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
pckev.h $w2, $w0, $w1
pckod.h $w3, $w0, $w1
ilvl.h $w4, $w0, $w1
ilvr.h $w5, $w0, $w1
ilvev.h $w6, $w0, $w1
ilvod.h $w0, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)

# Select the last lane of ws and the first lane of wt.
ld.b $w0, 0($4)
ldi.h $w2, 15
insert.h $w2[1], $0
vshf.h $w2, $w0, $w1
st.b $w2, 96($5)

# Each of bits 6 and 7 independently forces its result lane to zero.
ldi.h $w2, 64
ori $8, $0, 128
insert.h $w2[1], $8
vshf.h $w2, $w0, $w1
st.b $w2, 112($5)

# Lane zero is replaced from wt. Later lanes read its original value.
ldi.h $w2, 8
ori $8, $0, 1
insert.h $w2[0], $8
vshf.h $w2, $w2, $w1
st.b $w2, 128($5)
