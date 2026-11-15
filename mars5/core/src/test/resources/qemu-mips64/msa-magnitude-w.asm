# Test signed-minimum magnitude and ties between opposite signs.
ldi.w $w0, 1
slli.w $w0, $w0, 31
ldi.w $w1, -1
srli.w $w1, $w1, 1
daddiu $8, $0, -5
insert.w $w0[1], $8
ori $8, $0, 5
insert.w $w1[1], $8
min_a.w $w2, $w0, $w1
st.b $w2, 0($5)
min_a.w $w2, $w1, $w0
st.b $w2, 16($5)
max_a.w $w2, $w0, $w1
st.b $w2, 32($5)
max_a.w $w2, $w1, $w0
st.b $w2, 48($5)
