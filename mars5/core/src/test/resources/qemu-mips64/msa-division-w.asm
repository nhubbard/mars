# Compare truncated signed quotients and remainders, and unsigned division.
ldi.w $w0, -7
ldi.w $w1, 3
ldi.w $w2, -1
ldi.w $w3, 1
slli.w $w3, $w3, 31
div_s.w $w4, $w0, $w1
mod_s.w $w5, $w0, $w1
div_s.w $w6, $w3, $w1
mod_s.w $w7, $w3, $w1
div_u.w $w8, $w2, $w1
mod_u.w $w9, $w2, $w1
st.w $w4, 0($5)
st.w $w5, 16($5)
st.w $w6, 32($5)
st.w $w7, 48($5)
st.w $w8, 64($5)
st.w $w9, 80($5)
