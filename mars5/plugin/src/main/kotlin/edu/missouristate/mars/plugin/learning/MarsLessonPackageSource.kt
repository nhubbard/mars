package edu.missouristate.mars.plugin.learning

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.util.io.OSAgnosticPathUtil.expandUserHome
import com.intellij.util.io.HttpRequests
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/** A student-selected archive location. Both sources use the same immutable import preview. */
internal sealed interface MarsLessonPackageSource {
  fun prepare(
      limits: MarsLessonPackages.Limits = MarsLessonPackages.Limits(),
  ): MarsLessonPackages.Prepared

  data class Local(val archive: Path) : MarsLessonPackageSource {
    override fun prepare(limits: MarsLessonPackages.Limits) =
        MarsLessonPackages.prepare(archive, limits)
  }

  data class Remote(val uri: URI) : MarsLessonPackageSource {
    init {
      requireHttps(uri)
    }

    override fun prepare(limits: MarsLessonPackages.Limits): MarsLessonPackages.Prepared {
      val temporary = Files.createTempDirectory("mars-lesson-download-")
      try {
        val archive = temporary.resolve("package.zip")
        HttpRequests.request(uri.toASCIIString())
            .productNameAsUserAgent()
            .connectTimeout(10_000)
            .readTimeout(15_000)
            .redirectLimit(5)
            // The platform follows HTTPS redirects and rejects transport downgrades.
            .connect { request ->
              request.inputStream.use { input ->
                Files.newOutputStream(archive).use { output ->
                  // Request.saveToFile has no byte limit. Bound actual decoded bytes,
                  // including chunked/gzipped responses, before writing each chunk.
                  val buffer = ByteArray(16 * 1024)
                  var total = 0L
                  while (true) {
                    ProgressManager.checkCanceled()
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(count.toLong() <= limits.archiveBytes - total) {
                      "Lab download exceeds the archive size limit"
                    }
                    output.write(buffer, 0, count)
                    total += count
                  }
                }
              }
            }
        return MarsLessonPackages.prepare(archive, limits)
      } finally {
        FileUtil.delete(temporary.toFile())
      }
    }
  }

  companion object {
    fun parse(value: String): MarsLessonPackageSource {
      val text = value.trim()
      // Windows paths reject the colon in HTTPS links before URI validation can run.
      if (text.startsWith("https:", ignoreCase = true)) return Remote(URI.create(text))
      val path = Path.of(expandUserHome(text))
      require(path.isAbsolute) { "Enter an absolute package path or an HTTPS lab link" }
      return Local(path)
    }

    private fun requireHttps(uri: URI) {
      require(
          uri.scheme.equals("https", ignoreCase = true) &&
              uri.host != null &&
              uri.rawUserInfo == null &&
              uri.rawFragment == null &&
              (uri.port == -1 || uri.port in 1..65535),
      ) {
        "Enter an HTTPS lab link without embedded credentials or a fragment"
      }
    }
  }
}
