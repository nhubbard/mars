# This is a comment.
.data
myString: .asciiz "Hello, world!\n"

.text
main:
  # Print contents of myString to console
  li $v0, 4
  la $a0, myString
  syscall

  # Exit the application
  li $v0, 10
  syscall