# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.d $w0, 1
slli.d $w0, $w0, 63
ldi.d $w1, -1
srli.d $w1, $w1, 1
daddiu $8, $0, -7
insert.d $w0[1], $8
ori $8, $0, 2
insert.d $w1[1], $8
# Wrapping multiply and accumulation, including an input alias.
move.v $w3, $w0
move.v $w4, $w1
mulv.d $w2, $w0, $w1
st.b $w2, 0($5)
maddv.d $w3, $w0, $w3
st.b $w3, 16($5)
msubv.d $w4, $w0, $w4
st.b $w4, 32($5)
addv.d $w2, $w0, $w1
st.b $w2, 48($5)
subv.d $w2, $w0, $w1
st.b $w2, 64($5)
addvi.d $w2, $w0, 31
st.b $w2, 80($5)
subvi.d $w2, $w0, 31
st.b $w2, 96($5)
