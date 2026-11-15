# Convert signed values around the Q-format limits, including a NaN.
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $8, $0, 0x7ff8
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0011
sd $8, 0($4)
ori $8, $0, 0xc000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w1, 0($4)
ctcmsa $1, $0
ftq.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
