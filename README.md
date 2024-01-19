# MARS: MIPS simulator and IntelliJ plugin

This is a continuation of the original MARS MIPS simulator, rewritten in Kotlin for modern versions of Java.

Many parts of the IntelliJ plugin were taken either directly or indirectly from Niklas Persson (GitHub: @equadon).
I am indebted to him, as developing my own plugin from scratch has proved to be a challenging task, mostly due to
JetBrains completely rewriting the plugin development guide at the same time I'm writing this plugin.
Thank you very much for open-sourcing your MIPS plugin. I'm happy to carry the torch for newer versions of IntelliJ,
and for the rewritten MARS core that I've made.

Here is the original repository for the MIPS plugin: https://github.com/equadon/intellij-mips/tree/master

## To-Do List

* [ ] **Short Term:** Resolve all TODOs and deprecations hiding in the codebase
* [ ] **Short Term:** Replace any former compatibility elements, like explicit method encapsulation instead of property accessors
* [x] **Long Term:** Separate MARS core from Venus IDE package and turn it into an IntelliJ IDEA plugin