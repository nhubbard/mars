# Every form shares the running CRC and discards source bits above its width.
ld $8, 0($4)
lui $9, 0x89ab
ori $9, $9, 0xcdef
crc32b $9, $8, $9
sd $9, 0($5)
crc32h $9, $8, $9
sd $9, 8($5)
crc32w $9, $8, $9
sd $9, 16($5)
crc32d $9, $8, $9
sd $9, 24($5)
lui $9, 0x89ab
ori $9, $9, 0xcdef
crc32cb $9, $8, $9
sd $9, 32($5)
crc32ch $9, $8, $9
sd $9, 40($5)
crc32cw $9, $8, $9
sd $9, 48($5)
crc32cd $9, $8, $9
sd $9, 56($5)
