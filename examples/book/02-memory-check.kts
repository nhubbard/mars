case("load store and reload") {
  requireDataLabel("input")
  requireDataLabel("result")
  runUntil("load_value")
  expectWord("input", 21)
  expectWord("result", 0)
  step()
  expectRegister("t1", 21)
  runUntil("store_result")
  expectRegister("t2", 42)
  expectWord("result", 0)
  step()
  expectWord("result", 42)
  runUntil("done")
  expectRegister("t3", 42)
  runToExit()
}

case("negative word is sign extended on load") {
  setWord("input", -3)
  runUntil("store_result")
  expectRegister("t1", -3L)
  expectRegister("t2", -6L)
  runUntil("done")
  expectWord("result", -6)
  expectRegister("t3", -6L)
  runToExit()
}
