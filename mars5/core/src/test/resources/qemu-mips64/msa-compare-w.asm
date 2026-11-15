# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w1, -1
srli.w $w1, $w1, 1
daddiu $8, $0, -7
insert.w $w0[1], $8
ori $8, $0, 2
insert.w $w1[1], $8
# Equal lanes exercise the inclusive comparisons.
insve.w $w1[0], $w0[0]
max_s.w $w2, $w0, $w1
st.b $w2, 0($5)
max_u.w $w2, $w0, $w1
st.b $w2, 16($5)
min_s.w $w2, $w0, $w1
st.b $w2, 32($5)
min_u.w $w2, $w0, $w1
st.b $w2, 48($5)
ceq.w $w2, $w0, $w1
st.b $w2, 64($5)
clt_s.w $w2, $w0, $w1
st.b $w2, 80($5)
clt_u.w $w2, $w0, $w1
st.b $w2, 96($5)
cle_s.w $w2, $w0, $w1
st.b $w2, 112($5)
cle_u.w $w2, $w0, $w1
st.b $w2, 128($5)
