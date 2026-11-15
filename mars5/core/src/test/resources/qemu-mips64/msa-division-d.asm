# Compare truncated signed quotients and remainders, and unsigned division.
ldi.d $w0, -7
ldi.d $w1, 3
ldi.d $w2, -1
ldi.d $w3, 1
slli.d $w3, $w3, 63
div_s.d $w4, $w0, $w1
mod_s.d $w5, $w0, $w1
div_s.d $w6, $w3, $w1
mod_s.d $w7, $w3, $w1
div_u.d $w8, $w2, $w1
mod_u.d $w9, $w2, $w1
st.d $w4, 0($5)
st.d $w5, 16($5)
st.d $w6, 32($5)
st.d $w7, 48($5)
st.d $w8, 64($5)
st.d $w9, 80($5)
