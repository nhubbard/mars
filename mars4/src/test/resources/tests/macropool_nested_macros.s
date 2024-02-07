.macro first
li $a0, 1
.end_macro

.macro second
li $a1, 1
first
.end_macro

second
second
second
second