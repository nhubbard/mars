# The same word loads signed and unsigned. The two LDPC instructions
# share an aligned base even though their instruction addresses differ.
lwpc $9, 24
lwupc $10, 20
ldpc $11, 24
ldpc $12, 24
bc after_literals
nop
sd $0, 0($0)
nop
lui $13, 0xabcd
sd $0, 0($0)
after_literals:
lwpc $13, -16
sd $9, 0($5)
sd $10, 8($5)
sd $11, 16($5)
sd $12, 24($5)
sd $13, 32($5)
