# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.b $w0, 1
slli.b $w0, $w0, 7
ldi.b $w1, -1
srli.b $w1, $w1, 1
daddiu $8, $0, -7
insert.b $w0[1], $8
ori $8, $0, 2
insert.b $w1[1], $8
# Wrapping multiply and accumulation, including an input alias.
move.v $w3, $w0
move.v $w4, $w1
mulv.b $w2, $w0, $w1
st.b $w2, 0($5)
maddv.b $w3, $w0, $w3
st.b $w3, 16($5)
msubv.b $w4, $w0, $w4
st.b $w4, 32($5)
addv.b $w2, $w0, $w1
st.b $w2, 48($5)
subv.b $w2, $w0, $w1
st.b $w2, 64($5)
addvi.b $w2, $w0, 31
st.b $w2, 80($5)
subvi.b $w2, $w0, 31
st.b $w2, 96($5)
