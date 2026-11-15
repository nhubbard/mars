# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.h $w0, 1
slli.h $w0, $w0, 15
ldi.h $w1, -1
srli.h $w1, $w1, 1
daddiu $8, $0, -7
insert.h $w0[1], $8
ori $8, $0, 2
insert.h $w1[1], $8
# Equal lanes exercise the inclusive comparisons.
insve.h $w1[0], $w0[0]
max_s.h $w2, $w0, $w1
st.b $w2, 0($5)
max_u.h $w2, $w0, $w1
st.b $w2, 16($5)
min_s.h $w2, $w0, $w1
st.b $w2, 32($5)
min_u.h $w2, $w0, $w1
st.b $w2, 48($5)
ceq.h $w2, $w0, $w1
st.b $w2, 64($5)
clt_s.h $w2, $w0, $w1
st.b $w2, 80($5)
clt_u.h $w2, $w0, $w1
st.b $w2, 96($5)
cle_s.h $w2, $w0, $w1
st.b $w2, 112($5)
cle_u.h $w2, $w0, $w1
st.b $w2, 128($5)
