# Ordinary scaling, finite overflow and signed exact or inexact underflow.
ori $8, $0, 0x3ff8
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
ori $8, $0, 0x0000
dsll $8, $8, 16
dsll $8, $8, 16
dsll $8, $8, 16
ori $8, $8, 0x0003
sd $8, 0($4)
ori $8, $0, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xffff
dsll $8, $8, 16
ori $8, $8, 0xfbce
sd $8, 8($4)
ld.d $w1, 0($4)
ctcmsa $1, $0
fexp2.d $w2, $w0, $w1
cfcmsa $9, $1
st.d $w2, 0($5)
sw $9, 112($5)
