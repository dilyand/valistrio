package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config
import valistrio.core.http.Server
import valistrio.core.validate.{ConfluentSchemaRegistry, SchemaRegistry}

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] = {
    implicit val logger = Slf4jLogger.getLogger[IO]

    Config.make.flatMap { conf =>
      ConfluentSchemaRegistry.resource(conf.schemaRegistry).use { schemaRegistry =>
        new Server(conf.server, schemaRegistry).run.as(ExitCode.Success)
      }
    }
  }
}
