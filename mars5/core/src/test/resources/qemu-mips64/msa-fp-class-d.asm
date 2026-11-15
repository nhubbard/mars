# Distinguish sign, normality, zeros, infinities and both NaN types.
ori $8, $0, 0x7ff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0011
sd $8, 0($4)
ori $8, $0, 0x7ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0022
sd $8, 8($4)
ld.d $w0, 0($4)
fclass.d $w2, $w0
st.d $w2, 0($5)
ori $8, $0, 0xfff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x7ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
fclass.d $w2, $w0
st.d $w2, 16($5)
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
fclass.d $w2, $w0
st.d $w2, 32($5)
ori $8, $0, 0x8000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
sd $8, 0($4)
ori $8, $0, 0x0000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0001
sd $8, 8($4)
ld.d $w0, 0($4)
fclass.d $w2, $w0
st.d $w2, 48($5)
ori $8, $0, 0x8000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
sd $0, 8($4)
ld.d $w0, 0($4)
fclass.d $w2, $w0
st.d $w2, 64($5)
