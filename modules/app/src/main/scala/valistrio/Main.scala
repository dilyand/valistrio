package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import valistrio.core.Config.ServerConfig
import valistrio.core.http.Server

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val serverConf = ServerConfig("0.0.0.0", 8080, 1L * 1024L * 1024L)
    new Server(serverConf).run.as(ExitCode.Success)
  }
}
