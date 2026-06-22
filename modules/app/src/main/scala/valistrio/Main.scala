package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.apply._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config
import valistrio.core.http.Server
import valistrio.core.post.KafkaSink
import valistrio.core.validate.ConfluentSchemaRegistry

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    implicit val logger = Slf4jLogger.getLogger[IO]

    Config.make.flatMap { conf =>
      (ConfluentSchemaRegistry.resource(conf.schemaRegistry), KafkaSink.resource(conf.kafka)).tupled.use {
        case (schemaRegistry, sink) =>
          new Server(conf.server, schemaRegistry, sink).run.as(ExitCode.Success)
      }
    }
  }
}
