package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import valistrio.core.Config.ServerConfig
import valistrio.core.http.Server

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val serverConf = ServerConfig("0.0.0.0", 8080)
    new Server(serverConf).run.as(ExitCode.Success)
  }
}
