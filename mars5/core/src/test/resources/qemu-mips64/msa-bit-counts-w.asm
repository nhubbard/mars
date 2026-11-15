# Cover the full-width sign bit and a lane of all zeroes.
ldi.w $w0, 1
slli.w $w1, $w0, 31
ldi.w $w2, -1
insert.w $w2[1], $0
sll.w $w3, $w0, $w2
pcnt.w $w4, $w2
nlzc.w $w5, $w2
nloc.w $w6, $w2
st.b $w1, 0($5)
st.b $w3, 16($5)
st.b $w4, 32($5)
st.b $w5, 48($5)
st.b $w6, 64($5)

# Narrow to four signed/unsigned bits, then leave a whole-width lane intact.
ldi.w $w0, 127
daddiu $8, $0, -128
insert.w $w0[1], $8
sat_s.w $w2, $w0, 3
sat_u.w $w3, $w0, 3
sat_s.w $w4, $w0, 31
sat_u.w $w5, $w0, 31
st.b $w2, 80($5)
st.b $w3, 96($5)
st.b $w4, 112($5)
st.b $w5, 128($5)
