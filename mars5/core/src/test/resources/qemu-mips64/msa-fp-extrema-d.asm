# Compare extrema where numeric values tie in magnitude.
ori $8, $0, 0xc000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x8000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $8, $0, 0x4000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
sd $0, 8($4)
ld.d $w1, 0($4)
ctcmsa $1, $0
fmin.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
ctcmsa $1, $0
fmax.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 16($5)
sw $9, 116($5)
ctcmsa $1, $0
fmin_a.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 32($5)
sw $9, 120($5)
ctcmsa $1, $0
fmax_a.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 48($5)
sw $9, 124($5)
