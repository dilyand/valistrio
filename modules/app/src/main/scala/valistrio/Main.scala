package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import valistrio.core.http.Server

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    new Server().run.as(ExitCode.Success)
  }
}
