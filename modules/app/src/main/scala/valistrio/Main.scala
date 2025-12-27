package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import valistrio.core.Config.ServerConfig
import valistrio.core.http.Server

import scala.concurrent.duration.DurationInt

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val serverConf = ServerConfig("0.0.0.0", 8080, 2L * 1024L * 1024L, 5.seconds)
    new Server(serverConf).run.as(ExitCode.Success)
  }
}
