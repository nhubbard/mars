package edu.missouristate.mars.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import edu.missouristate.mars.ProcessingException;
import edu.missouristate.mars.Settings;
import edu.missouristate.mars.api.MarsGuiProxy;
import edu.missouristate.mars.api.SessionSyscallEnvironment;
import edu.missouristate.mars.di.CoreModule;
import edu.missouristate.mars.di.DaggerMarsComponent;
import edu.missouristate.mars.simulator.ProgramArgumentList;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.Adler32;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PngDecoderTest {
    private static final Path SOURCES = Path.of("src/main/mips").toAbsolutePath();
    private static final int DISPLAY_STRIDE = 512;
    private static final String SUCCESS = "PNG displayed.";
    private static final String INVALID = "Invalid PNG or corrupt compressed image.";
    private static final MarsGuiProxy HEADLESS = new MarsGuiProxy() {
        public boolean isGuiMode() { return false; }
        public void postMessage(String text) {}
        public void postRunMessage(String text) {}
        public String readInput(int length) { throw new UnsupportedOperationException("Use the console input stream"); }
        public String readInputWithPrompt(String prompt) { throw new UnsupportedOperationException("Use the console input stream"); }
        public void clearRunConsole() {}
    };

    @TempDir
    Path temporary;

    @Test
    void drawsEveryPixelOfTheFullSizeImage() throws Exception {
        var input = SOURCES.resolve("torture.png");
        var expected = ImageIO.read(input.toFile());
        assertEquals(DISPLAY_STRIDE, expected.getWidth());
        assertEquals(DISPLAY_STRIDE, expected.getHeight());
        run(input, expected, false, SUCCESS);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void decodesFixedAndStoredBlocksWithAllRowFilters(boolean delayedBranches) throws Exception {
        var filtered = filteredScanlines();
        var fixed = png(2, 5, 2, fixedZlib(filtered));
        var stored = png(2, 5, 2, storedZlib(filtered));
        for (var fixture : List.of(fixed, stored)) {
            var path = Files.write(temporary.resolve("blocks.png"), fixture);
            run(path, ImageIO.read(path.toFile()), delayedBranches, SUCCESS);
        }
    }

    @Test
    void rejectsTruncationBadChecksumsUnknownFiltersAndUnsupportedFormats() throws Exception {
        var filtered = filteredScanlines();
        var zlib = storedZlib(filtered);
        var valid = png(2, 5, 2, zlib);
        var truncated = valid.clone();
        truncated[11] = -1;
        var crc = valid.clone();
        crc[crc.length - 1] ^= 1;
        var adler = zlib.clone();
        adler[adler.length - 1] ^= 1;
        filtered[0] = 5;
        for (var invalid : List.of(truncated, crc, png(2, 5, 2, adler),
                png(2, 5, 2, storedZlib(filtered)), png(1, 5, 2, zlib))) {
            var path = Files.write(temporary.resolve("invalid.png"), invalid);
            run(path, null, false, INVALID);
        }
        var rgba = Files.write(temporary.resolve("rgba.png"), png(2, 5, 6, zlib));
        run(rgba, null, false, "Expected a non-interlaced 8-bit RGB PNG");
        run(temporary.resolve("missing.png"), null, false, "Cannot read PNG");
    }

    private static void run(Path input, BufferedImage expected, boolean delayedBranches, String message) throws Exception {
        var settings = new Settings();
        settings.getSimulationSettings().setMemoryConfiguration("Default")
            .setDelayedBranchingEnabled(delayedBranches).setExtendedAssemblerEnabled(true);
        var output = new ByteArrayOutputStream();
        try (var session = DaggerMarsComponent.builder().coreModule(new CoreModule(settings, 32)).guiProxy(HEADLESS).build().context();
             var console = new PrintStream(output, true, StandardCharsets.UTF_8);
             var syscalls = new SessionSyscallEnvironment(session.memory,
                 new ByteArrayInputStream(new byte[] {10}), console, console, input.getParent())) {
            var program = session.assemble(List.of(SOURCES.resolve("viewer.asm")));
            if (!input.getFileName().toString().equals("torture.png")) {
                new ProgramArgumentList(new String[] {input.getFileName().toString()}).storeProgramArguments(session);
            }
            program.setSyscallEnvironment(syscalls);
            assertTrue(program.simulate(250_000_000), "The decoder must exit within the test budget");
            assertEquals(expected == null ? 1 : 0, program.getExitCode(), output.toString(StandardCharsets.UTF_8));
            assertTrue(output.toString(StandardCharsets.UTF_8).contains(message), output.toString(StandardCharsets.UTF_8));
            int base = session.memory.heapBaseAddress;
            if (expected == null) {
                assertEquals(0, session.memory.getWordNoNotify(base));
                assertEquals(0, session.memory.getWordNoNotify(base + 4 * (DISPLAY_STRIDE * DISPLAY_STRIDE - 1)));
                return;
            }
            for (int row = 0; row < expected.getHeight(); row++) {
                for (int column = 0; column < expected.getWidth(); column++) {
                    int address = base + 4 * (row * DISPLAY_STRIDE + column);
                    int rgb = expected.getRGB(column, row) & 0xffffff;
                    int observed = session.memory.getWordNoNotify(address);
                    if (rgb != observed) {
                        assertEquals(rgb, observed, "Pixel at (" + column + ", " + row + ")");
                    }
                }
            }
        } catch (ProcessingException failure) {
            throw new AssertionError(failure.errors().generateErrorAndWarningReport(), failure);
        }
    }

    private static byte[] filteredScanlines() {
        return new byte[] {
            0, 11, 22, 33, 44, 55, 66,
            1, 3, 9, 7, -4, 100, 2,
            2, 0, 6, 80, -41, 17, 20,
            3, 5, -5, 99, 1, 7, 31,
            4, 2, 10, 6, 17, -18, 24
        };
    }

    private static byte[] storedZlib(byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        var deflater = new Deflater(Deflater.NO_COMPRESSION);
        try (var stream = new DeflaterOutputStream(output, deflater)) {
            stream.write(bytes);
        } finally {
            deflater.end();
        }
        assertEquals(0, (output.toByteArray()[2] >>> 1) & 3);
        return output.toByteArray();
    }

    private static byte[] fixedZlib(byte[] bytes) throws Exception {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[] {0x78, 0x01});
        int pending = 3; // BFINAL=1, BTYPE=01.
        int bits = 3;
        for (byte value : bytes) {
            int symbol = Byte.toUnsignedInt(value);
            int code = symbol < 144 ? 0x30 + symbol : 0x190 + symbol - 144;
            int length = symbol < 144 ? 8 : 9;
            pending |= (Integer.reverse(code) >>> (32 - length)) << bits;
            bits += length;
            while (bits >= 8) {
                output.write(pending);
                pending >>>= 8;
                bits -= 8;
            }
        }
        // EOB is seven zero bits. At most two bytes finish and pad the block.
        output.write(pending);
        if (bits > 1) output.write(0);
        var adler = new Adler32();
        adler.update(bytes);
        new DataOutputStream(output).writeInt((int) adler.getValue());
        return output.toByteArray();
    }

    private static byte[] png(int width, int height, int type, byte[] zlib) throws Exception {
        var output = new ByteArrayOutputStream();
        var data = new DataOutputStream(output);
        data.writeLong(0x89504e470d0a1a0aL);
        var header = new ByteArrayOutputStream();
        var fields = new DataOutputStream(header);
        fields.writeInt(width);
        fields.writeInt(height);
        fields.write(new byte[] {8, (byte) type, 0, 0, 0});
        chunk(data, "IHDR", header.toByteArray());
        chunk(data, "IDAT", zlib);
        chunk(data, "IEND", new byte[0]);
        return output.toByteArray();
    }

    private static void chunk(DataOutputStream output, String name, byte[] contents) throws Exception {
        byte[] type = name.getBytes(StandardCharsets.US_ASCII);
        var crc = new CRC32();
        crc.update(type);
        crc.update(contents);
        output.writeInt(contents.length);
        output.write(type);
        output.write(contents);
        output.writeInt((int) crc.getValue());
    }
}
