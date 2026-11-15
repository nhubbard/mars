# Pack and interleave distinct vectors. Exercise destination/source aliasing.
ld.b $w0, 0($4)
ld.b $w1, 16($4)
pckev.w $w2, $w0, $w1
pckod.w $w3, $w0, $w1
ilvl.w $w4, $w0, $w1
ilvr.w $w5, $w0, $w1
ilvev.w $w6, $w0, $w1
ilvod.w $w0, $w0, $w1
st.b $w2, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)
st.b $w0, 80($5)

# Select the last lane of ws and the first lane of wt.
ld.b $w0, 0($4)
ldi.w $w2, 7
insert.w $w2[1], $0
vshf.w $w2, $w0, $w1
st.b $w2, 96($5)

# Each of bits 6 and 7 independently forces its result lane to zero.
ldi.w $w2, 64
ori $8, $0, 128
insert.w $w2[1], $8
vshf.w $w2, $w0, $w1
st.b $w2, 112($5)

# Lane zero is replaced from wt. Later lanes read its original value.
ldi.w $w2, 4
ori $8, $0, 1
insert.w $w2[0], $8
vshf.w $w2, $w2, $w1
st.b $w2, 128($5)
