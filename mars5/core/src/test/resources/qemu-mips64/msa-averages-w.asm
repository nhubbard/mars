# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w1, -1
srli.w $w1, $w1, 1
daddiu $8, $0, -7
insert.w $w0[1], $8
ori $8, $0, 2
insert.w $w1[1], $8
add_a.w $w2, $w0, $w1
st.b $w2, 0($5)
asub_s.w $w2, $w0, $w1
st.b $w2, 16($5)
asub_u.w $w2, $w0, $w1
st.b $w2, 32($5)
ave_s.w $w2, $w0, $w1
st.b $w2, 48($5)
ave_u.w $w2, $w0, $w1
st.b $w2, 64($5)
aver_s.w $w2, $w0, $w1
st.b $w2, 80($5)
aver_u.w $w2, $w0, $w1
st.b $w2, 96($5)
subs_u.w $w2, $w0, $w1
st.b $w2, 112($5)
# Equal maximum unsigned lanes must average without wrapping.
ldi.w $w0, -1
aver_u.w $w2, $w0, $w0
st.b $w2, 128($5)
