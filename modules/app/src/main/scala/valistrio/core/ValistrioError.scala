package valistrio.core

sealed abstract class ValistrioError extends Throwable {
  val msg: String
}

object ValistrioError {
  final case class ConfigParsingError(error: String) extends ValistrioError {
    val msg = s"Could not parse base64-encoded string as HOCON: $error"
  }
}
