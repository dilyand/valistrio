package valistrio.core

import scala.concurrent.duration.FiniteDuration

object Config {
  final case class ServerConfig(host: String, port: Int, maxBytes: Long, requestTimeout: FiniteDuration)
}
