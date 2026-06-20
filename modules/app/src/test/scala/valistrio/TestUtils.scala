package valistrio

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.io.Source

object TestUtils {
  def fromFile(path: String): String = {
    val source = Source.fromFile(path)
    val j      = source.mkString
    source.close()
    j
  }

  def b64(hocon: String): String =
    Base64.getEncoder.encodeToString(hocon.getBytes(StandardCharsets.UTF_8))
}
