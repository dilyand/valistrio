package valistrio

import scala.io.Source

object TestUtils {
  def fromFile(path: String): String = {
    val source = Source.fromFile(path)
    val j      = source.mkString
    source.close()
    j
  }
}
