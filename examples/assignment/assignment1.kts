case("double a positive number") {
  requireTextLabel("double")
  requireDataLabel("result")
  setRegister("a0", 21)
  startAt("double")
  step()
  expectRegister("v0", 42)
  runUntil("done")
  expectWord("result", 42)
  runToExit()
}

case("double a negative number") {
  setRegister("a0", -3)
  startAt("double")
  runUntil("done")
  expectWord("result", -6)
  runToExit()
}
