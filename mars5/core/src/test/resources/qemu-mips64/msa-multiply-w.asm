# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w1, -1
srli.w $w1, $w1, 1
daddiu $8, $0, -7
insert.w $w0[1], $8
ori $8, $0, 2
insert.w $w1[1], $8
# Wrapping multiply and accumulation, including an input alias.
move.v $w3, $w0
move.v $w4, $w1
mulv.w $w2, $w0, $w1
st.b $w2, 0($5)
maddv.w $w3, $w0, $w3
st.b $w3, 16($5)
msubv.w $w4, $w0, $w4
st.b $w4, 32($5)
addv.w $w2, $w0, $w1
st.b $w2, 48($5)
subv.w $w2, $w0, $w1
st.b $w2, 64($5)
addvi.w $w2, $w0, 31
st.b $w2, 80($5)
subvi.w $w2, $w0, 31
st.b $w2, 96($5)
