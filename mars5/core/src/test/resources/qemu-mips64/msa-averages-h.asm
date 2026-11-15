# Mix signed extremes, opposite signs, equal lanes, and odd sums.
ldi.h $w0, 1
slli.h $w0, $w0, 15
ldi.h $w1, -1
srli.h $w1, $w1, 1
daddiu $8, $0, -7
insert.h $w0[1], $8
ori $8, $0, 2
insert.h $w1[1], $8
add_a.h $w2, $w0, $w1
st.b $w2, 0($5)
asub_s.h $w2, $w0, $w1
st.b $w2, 16($5)
asub_u.h $w2, $w0, $w1
st.b $w2, 32($5)
ave_s.h $w2, $w0, $w1
st.b $w2, 48($5)
ave_u.h $w2, $w0, $w1
st.b $w2, 64($5)
aver_s.h $w2, $w0, $w1
st.b $w2, 80($5)
aver_u.h $w2, $w0, $w1
st.b $w2, 96($5)
subs_u.h $w2, $w0, $w1
st.b $w2, 112($5)
# Equal maximum unsigned lanes must average without wrapping.
ldi.h $w0, -1
aver_u.h $w2, $w0, $w0
st.b $w2, 128($5)
