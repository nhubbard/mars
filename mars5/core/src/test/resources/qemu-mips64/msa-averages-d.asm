# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.d $w0, 1
slli.d $w0, $w0, 63
ldi.d $w1, -1
srli.d $w1, $w1, 1
daddiu $8, $0, -7
insert.d $w0[1], $8
ori $8, $0, 2
insert.d $w1[1], $8
add_a.d $w2, $w0, $w1
st.b $w2, 0($5)
asub_s.d $w2, $w0, $w1
st.b $w2, 16($5)
asub_u.d $w2, $w0, $w1
st.b $w2, 32($5)
ave_s.d $w2, $w0, $w1
st.b $w2, 48($5)
ave_u.d $w2, $w0, $w1
st.b $w2, 64($5)
aver_s.d $w2, $w0, $w1
st.b $w2, 80($5)
aver_u.d $w2, $w0, $w1
st.b $w2, 96($5)
subs_u.d $w2, $w0, $w1
st.b $w2, 112($5)
# Equal maximum unsigned lanes must average without wrapping.
ldi.d $w0, -1
aver_u.d $w2, $w0, $w0
st.b $w2, 128($5)
