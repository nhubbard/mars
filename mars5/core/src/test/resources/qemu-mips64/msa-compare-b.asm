# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.b $w0, 1
slli.b $w0, $w0, 7
ldi.b $w1, -1
srli.b $w1, $w1, 1
daddiu $8, $0, -7
insert.b $w0[1], $8
ori $8, $0, 2
insert.b $w1[1], $8
# Equal lanes exercise the inclusive comparisons.
insve.b $w1[0], $w0[0]
max_s.b $w2, $w0, $w1
st.b $w2, 0($5)
max_u.b $w2, $w0, $w1
st.b $w2, 16($5)
min_s.b $w2, $w0, $w1
st.b $w2, 32($5)
min_u.b $w2, $w0, $w1
st.b $w2, 48($5)
ceq.b $w2, $w0, $w1
st.b $w2, 64($5)
clt_s.b $w2, $w0, $w1
st.b $w2, 80($5)
clt_u.b $w2, $w0, $w1
st.b $w2, 96($5)
cle_s.b $w2, $w0, $w1
st.b $w2, 112($5)
cle_u.b $w2, $w0, $w1
st.b $w2, 128($5)
