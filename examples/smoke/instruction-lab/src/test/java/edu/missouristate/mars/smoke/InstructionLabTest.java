package edu.missouristate.mars.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class InstructionLabTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void executesTheBinaryWorkbenchWithAndWithoutDelaySlots(boolean delayedBranches) throws Exception {
        var input = """
            1
            -1
            2
            123456789
            3
            9
            9
            8
            -1
            4
            9
            10
            8
            0
            33
            5
            1
            1
            3
            64
            0
            0
            0
            0
            """;
        var bytes = new ByteArrayOutputStream();
        try (var output = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
            InstructionLab.runWorkbench(Path.of("src/main/mips/workbench.asm"),
                new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output, delayedBranches);
        }
        var result = bytes.toString(StandardCharsets.UTF_8);
        assertEquals(List.of("0xffffffff", "0x2528ffff", "0x012a4021", "0x80000000"),
            result.lines().filter(line -> line.startsWith("Hex: ")).map(line -> line.substring(5)).toList());
        assertTrue(result.contains("Unsigned decimal: 4294967295"), result);
        assertTrue(result.contains("Set bits: 32"), result);
        assertTrue(result.contains("CRC-32: 0xcbf43926\nBytes: 9"), result);
        assertTrue(result.contains("A field is outside its permitted range."), result);
    }
}
