# The single reference CPU reports ID zero and supports ordinary barriers.
rdhwr $8, $0
rdhwr $9, $0, 0
sd $8, 0($5)
sd $9, 8($5)
daddiu $8, $0, 17
daddiu $9, $0, 23
ehb
ssnop
pause
sync
sync 0
pref 0, 0($4)
synci 0($4)
sd $8, 16($5)
sd $9, 24($5)
