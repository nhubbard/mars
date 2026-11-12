package edu.missouristate.mars.api;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackInputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;

/** The disk source decoding rules shared by assembly and editor snapshots. */
public final class AssemblySourceReader {
  private AssemblySourceReader() {}

  /** Opens the file as assembly source. The caller owns the returned reader. */
  public static Reader open(Path file) throws IOException {
    var input = new PushbackInputStream(Files.newInputStream(file), 4);
    try {
      byte[] prefix = input.readNBytes(4);
      Charset charset = Charset.defaultCharset();
      int skip = 0;
      for (var bom : SOURCE_BOMS) {
        byte[] signature = bom.signature();
        if (prefix.length >= signature.length
            && Arrays.equals(prefix, 0, signature.length, signature, 0, signature.length)) {
          charset = bom.charset();
          skip = bom.skip();
          break;
        }
      }
      input.unread(prefix, skip, prefix.length - skip);
      return new InputStreamReader(input, charset);
    } catch (IOException | RuntimeException failure) {
      try (input) {
        throw failure;
      }
    }
  }

  private record SourceBom(byte[] signature, Charset charset, int skip) {
    SourceBom(String signature, Charset charset, int skip) {
      this(HexFormat.of().parseHex(signature), charset, skip);
    }
  }

  // UTF-32LE shares its first two bytes with UTF-16LE. Match the longer marker first.
  // The UTF-16 and UTF-32 decoders consume their markers and select the byte order themselves.
  private static final Charset UTF_32 = Charset.forName("UTF-32");
  private static final List<SourceBom> SOURCE_BOMS =
      List.of(
          new SourceBom("efbbbf", StandardCharsets.UTF_8, 3),
          new SourceBom("0000feff", UTF_32, 0),
          new SourceBom("fffe0000", UTF_32, 0),
          new SourceBom("feff", StandardCharsets.UTF_16, 0),
          new SourceBom("fffe", StandardCharsets.UTF_16, 0));
}
