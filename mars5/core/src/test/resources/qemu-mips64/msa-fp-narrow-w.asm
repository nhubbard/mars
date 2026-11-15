# Pack both source vectors. Round halfway and tiny interchange values upward.
ori $8, $0, 0x3ff0
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x1000
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0x36a0
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w0, 0($4)
ori $8, $0, 0xbff0
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x1000
dsll $8, $8, 16
sd $8, 0($4)
ori $8, $0, 0xb690
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
sd $8, 8($4)
ld.d $w1, 0($4)
ori $10, $0, 2
ctcmsa $1, $10
fexdo.w $w2, $w0, $w1
cfcmsa $9, $1
st.w $w2, 0($5)
sw $9, 112($5)
