# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.h $w0, 1
slli.h $w0, $w0, 15
ldi.h $w1, -1
srli.h $w1, $w1, 1
daddiu $8, $0, -7
insert.h $w0[1], $8
ori $8, $0, 2
insert.h $w1[1], $8
# Wrapping multiply and accumulation, including an input alias.
move.v $w3, $w0
move.v $w4, $w1
mulv.h $w2, $w0, $w1
st.b $w2, 0($5)
maddv.h $w3, $w0, $w3
st.b $w3, 16($5)
msubv.h $w4, $w0, $w4
st.b $w4, 32($5)
addv.h $w2, $w0, $w1
st.b $w2, 48($5)
subv.h $w2, $w0, $w1
st.b $w2, 64($5)
addvi.h $w2, $w0, 31
st.b $w2, 80($5)
subvi.h $w2, $w0, 31
st.b $w2, 96($5)
