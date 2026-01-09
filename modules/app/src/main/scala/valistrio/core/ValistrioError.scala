package valistrio.core

sealed abstract class ValistrioError extends Throwable {
  val msg: String
}

object ValistrioError {
  sealed trait ConfigError extends ValistrioError
  final object ConfigError {
    final case class NotBase64(error: String) extends ConfigError {
      val msg = s"Could not base64-decode string. Error: $error"
    }

    final case class TypesafeConfigError(error: String) extends ConfigError {
      val msg = s"Could not derive Typesafe Config instance from string. Error: $error"
    }

    final case class ParsingFailure(error: String) extends ConfigError {
      val msg = s"Could not parse Typesafe Config. Error: $error"
    }
  }
}
