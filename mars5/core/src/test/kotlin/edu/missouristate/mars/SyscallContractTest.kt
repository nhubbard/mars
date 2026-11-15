package edu.missouristate.mars

import edu.missouristate.mars.api.SyscallEnvironment
import edu.missouristate.mars.core.InstructionContext
import edu.missouristate.mars.mips.hardware.IntegerRegisterFile
import edu.missouristate.mars.mips.instructions.SyscallRegistry
import edu.missouristate.mars.mips.instructions.syscalls.Syscall
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Random
import java.util.function.Predicate
import org.assertj.core.api.Assertions
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

internal class SyscallContractTest {
    @ParameterizedTest
    @MethodSource("services")
    fun everySupportedServiceUsesItsSessionAndEnvironment(number: Int) {
        TestSessions.create().use { session ->
            val calls = mutableListOf<String>()
            val wallTime = 0x0123456789abcdefL
            val environment =
                Proxy.newProxyInstance(
                    SyscallEnvironment::class.java.classLoader,
                    arrayOf<Class<*>>(SyscallEnvironment::class.java),
                    InvocationHandler { proxy: Any?, method: Method?, args: Array<Any?>? ->
                        if (method!!.declaringClass == Any::class.java) {
                            return@InvocationHandler when (method.name) {
                                "equals" ->
                                    proxy === checkNotNull(args)[0]
                                "hashCode" -> System.identityHashCode(proxy)
                                "toString" -> "Test syscall environment"
                                else -> throw AssertionError(method)
                            }
                        }
                        calls += "${method.name}${args.orEmpty().contentToString()}"
                        when (method.name) {
                            "readInt" -> 7
                            "readFloat" -> 1.5f
                            "readDouble" -> 2.5
                            "readChar" -> 'Z'
                            "readString" -> "hi"
                            "showInputDialog" -> "42"
                            "showConfirmDialog" -> 2
                            "currentTimeMillis" -> wallTime
                            "openFile" -> 3
                            "readFromFile" -> {
                                val buffer =
                                    checkNotNull(args)[1] as ByteArray
                                buffer[0] = 1
                                buffer[1] = 2
                                buffer[2] = 3
                                3
                            }

                            "writeToFile" -> checkNotNull(args)[2]
                            else -> null
                        }
                    },
                ) as SyscallEnvironment
            val context = InstructionContext(session, environment)
            val address: Int = session.memory.dataBaseAddress
            val buffer = address + 32
            val message: ByteArray = "hello\u0000".toByteArray()
            for (i in message.indices) {
                session.memory.setByte(address + i, message[i].toInt())
            }
            for (i in 0..3) {
                session.memory.setByte(buffer + i, 65 + i)
            }
            val registers: IntegerRegisterFile = session.registerFile
            registers.updateRegister(4, address)
            registers.updateRegister(5, buffer)
            registers.updateRegister(6, 8)
            session.coprocessor1.setRegisterToFloat(12, 1.5f)
            if (number == 3 || number == 58) {
                session.coprocessor1.setRegisterPairToDouble(12, 2.5)
            }
            when (number) {
                1,
                34,
                35 -> registers.updateRegister(4, 42)
                36 -> registers.updateRegister(4, -1)
                8 -> {
                    registers.updateRegister(4, buffer)
                    registers.updateRegister(5, 8)
                }

                9 -> registers.updateRegister(4, 4)
                11 -> registers.updateRegister(4, 'A'.code)
                13 -> registers.updateRegister(5, 0)
                14,
                15 -> {
                    registers.updateRegister(4, 3)
                    registers.updateRegister(6, 3)
                }

                16 -> registers.updateRegister(4, 3)
                17 -> registers.updateRegister(4, 7)
                31,
                33 -> {
                    registers.updateRegister(4, 60)
                    registers.updateRegister(5, 0)
                    registers.updateRegister(6, 0)
                    registers.updateRegister(7, 10)
                }

                32 -> registers.updateRegister(4, 250)
                40 -> {
                    registers.updateRegister(4, 1)
                    registers.updateRegister(5, 123)
                }

                41,
                42,
                43,
                44 -> {
                    registers.updateRegister(4, 1)
                    registers.updateRegister(5, 10)
                }

                55 -> registers.updateRegister(5, 1)
                56 -> registers.updateRegister(5, 42)
            }
            session.randomStreams.setSeed(1, 123)
            val heap: Int = session.memory.heapAddress
            val service = checkNotNull(session.syscalls.findByNumber(number)) { "Missing syscall $number" }
            if (number == 10 || number == 17) {
                val failure =
                    Assertions.catchThrowable { service.simulate(null, context) }
                Assertions.assertThat<Throwable?>(failure)
                    .isInstanceOf(ProcessingException::class.java)
                Assertions.assertThat((failure as ProcessingException).errors())
                    .isNull()
                Assertions.assertThat(context.exitCode).isEqualTo(if (number == 17) 7 else 0)
            } else {
                service.simulate(null, context)
            }
            when (number) {
                1 -> Assertions.assertThat(calls).containsExactly("printInt[42]")
                2 -> Assertions.assertThat(calls).containsExactly("printFloat[1.5]")
                3 -> Assertions.assertThat(calls).containsExactly("printDouble[2.5]")
                4 ->
                    Assertions.assertThat(calls)
                        .containsExactly(
                            "printChar[h]",
                            "printChar[e]",
                            "printChar[l]",
                            "printChar[l]",
                            "printChar[o]",
                        )

                5 -> Assertions.assertThat(registers.getValue(2)).isEqualTo(7)
                6 ->
                    Assertions.assertThat(session.coprocessor1.getFloatFromRegister(0))
                        .isEqualTo(1.5f)
                7 ->
                    Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(0))
                        .isEqualTo(2.5)
                8 -> {
                    Assertions.assertThat(session.memory.getByte(buffer)).isEqualTo('h'.code)
                    Assertions.assertThat(session.memory.getByte(buffer + 2)).isEqualTo('\n'.code)
                    Assertions.assertThat(session.memory.getByte(buffer + 3)).isZero()
                }

                9 -> {
                    Assertions.assertThat(registers.getValue(2)).isEqualTo(heap)
                    Assertions.assertThat(session.memory.heapAddress).isEqualTo(heap + 4)
                }

                10,
                17 ->
                    Assertions.assertThat(calls)
                        .containsExactly("exit[" + (if (number == 17) 7 else 0) + "]")

                11 -> Assertions.assertThat(calls).containsExactly("printChar[A]")
                12 -> Assertions.assertThat(registers.getValue(2)).isEqualTo('Z'.code)
                13,
                14,
                15 -> {
                    Assertions.assertThat(registers.getValue(2)).isEqualTo(3)
                    Assertions.assertThat(calls).hasSize(1)
                    if (number == 14) {
                        Assertions.assertThat(session.memory.getByte(buffer + 2)).isEqualTo(3)
                    }
                }

                16 -> Assertions.assertThat(calls).containsExactly("closeFile[3]")
                30 -> {
                    Assertions.assertThat(calls).containsExactly("currentTimeMillis[]")
                    Assertions.assertThat(registers.getValue(4)).isEqualTo(-0x76543211)
                    Assertions.assertThat(registers.getValue(5)).isEqualTo(0x01234567)
                }

                31,
                33 ->
                    Assertions.assertThat(calls)
                        .containsExactly("playTone[60, 0, 0, 10, " + (number == 33) + "]")

                32 -> Assertions.assertThat(calls).containsExactly("sleepMillis[250]")
                34 ->
                    Assertions.assertThat(calls).containsExactly("printString[0x0000002a]")
                35 ->
                    Assertions.assertThat(calls)
                        .containsExactly("printString[00000000000000000000000000101010]")

                36 ->
                    Assertions.assertThat(calls).containsExactly("printString[4294967295]")
                40,
                41 ->
                    Assertions.assertThat(
                            if (number == 40) session.randomStreams.get(1).nextInt()
                            else registers.getValue(4),
                        )
                        .isEqualTo(Random(123).nextInt())

                42 ->
                    Assertions.assertThat(registers.getValue(4)).isEqualTo(Random(123).nextInt(10))
                43 ->
                    Assertions.assertThat(session.coprocessor1.getFloatFromRegister(0))
                        .isEqualTo(Random(123).nextFloat())

                44 ->
                    Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(0))
                        .isEqualTo(Random(123).nextDouble())

                50 -> Assertions.assertThat(registers.getValue(4)).isEqualTo(2)
                51 -> {
                    Assertions.assertThat(registers.getValue(4)).isEqualTo(42)
                    Assertions.assertThat(registers.getValue(5)).isZero()
                }

                52 -> {
                    Assertions.assertThat(session.coprocessor1.getFloatFromRegister(0))
                        .isEqualTo(42f)
                    Assertions.assertThat(registers.getValue(5)).isZero()
                }

                53 -> {
                    Assertions.assertThat(session.coprocessor1.getDoubleFromRegisterPair(0))
                        .isEqualTo(42.0)
                    Assertions.assertThat(registers.getValue(5)).isZero()
                }

                54 -> {
                    Assertions.assertThat(session.memory.getByte(buffer)).isEqualTo('4'.code)
                    Assertions.assertThat(session.memory.getByte(buffer + 2)).isEqualTo('\n'.code)
                    Assertions.assertThat(registers.getValue(5)).isZero()
                }

                55,
                56,
                57,
                58,
                59 ->
                    Assertions.assertThat(calls)
                        .hasSize(1)
                        .allMatch(
                            Predicate { call: String ->
                                call.startsWith("showMessageDialog[hello")
                            },
                        )

                60 -> Assertions.assertThat(calls).containsExactly("clearConsole[]")
                else -> throw AssertionError("Missing syscall vector $number")
            }
        }
    }

    @Test
    fun mutableServiceNumbersAreNotSharedAcrossSessions() {
        val first = SyscallRegistry()
        val second = SyscallRegistry()
        checkNotNull(first.findByNumber(1)) { "Missing PrintInt" }.number = 101
        val unchanged = checkNotNull(second.findByNumber(1)) { "Missing PrintInt" }
        Assertions.assertThat(unchanged.number).isEqualTo(1)
        Assertions.assertThat<Syscall?>(first.services()).hasSize(40)
    }

    companion object {
        @JvmStatic
        fun services(): List<Int> {
            return SyscallRegistry().services().map(Syscall::number).sorted()
        }
    }
}
