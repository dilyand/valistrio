package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import valistrio.core.Config
import valistrio.core.http.Server

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    val conf = Config.make

    conf.map(c => new Server(c.server)).flatMap(_.run.as(ExitCode.Success))
  }
}
