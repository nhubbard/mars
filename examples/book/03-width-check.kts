case("word and doubleword results") {
  runUntil("word_step")
  expectRegister("t0", 2147483647L)
  step()
  expectRegister("t1", -2147483648L)
  step()
  expectRegister("t2", 2147483648L)
  runToExit()
}
