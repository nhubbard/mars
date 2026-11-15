# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.b $w0, 1
slli.b $w0, $w0, 7
ldi.b $w1, -1
srli.b $w1, $w1, 1
daddiu $8, $0, -7
insert.b $w0[1], $8
ori $8, $0, 2
insert.b $w1[1], $8
add_a.b $w2, $w0, $w1
st.b $w2, 0($5)
asub_s.b $w2, $w0, $w1
st.b $w2, 16($5)
asub_u.b $w2, $w0, $w1
st.b $w2, 32($5)
ave_s.b $w2, $w0, $w1
st.b $w2, 48($5)
ave_u.b $w2, $w0, $w1
st.b $w2, 64($5)
aver_s.b $w2, $w0, $w1
st.b $w2, 80($5)
aver_u.b $w2, $w0, $w1
st.b $w2, 96($5)
subs_u.b $w2, $w0, $w1
st.b $w2, 112($5)
# Equal maximum unsigned lanes must average without wrapping.
ldi.b $w0, -1
aver_u.b $w2, $w0, $w0
st.b $w2, 128($5)
