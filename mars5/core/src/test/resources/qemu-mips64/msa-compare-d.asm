# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.d $w0, 1
slli.d $w0, $w0, 63
ldi.d $w1, -1
srli.d $w1, $w1, 1
daddiu $8, $0, -7
insert.d $w0[1], $8
ori $8, $0, 2
insert.d $w1[1], $8
# Equal lanes exercise the inclusive comparisons.
insve.d $w1[0], $w0[0]
max_s.d $w2, $w0, $w1
st.b $w2, 0($5)
max_u.d $w2, $w0, $w1
st.b $w2, 16($5)
min_s.d $w2, $w0, $w1
st.b $w2, 32($5)
min_u.d $w2, $w0, $w1
st.b $w2, 48($5)
ceq.d $w2, $w0, $w1
st.b $w2, 64($5)
clt_s.d $w2, $w0, $w1
st.b $w2, 80($5)
clt_u.d $w2, $w0, $w1
st.b $w2, 96($5)
cle_s.d $w2, $w0, $w1
st.b $w2, 112($5)
cle_u.d $w2, $w0, $w1
st.b $w2, 128($5)
