package valistrio

import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.apply._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config
import valistrio.core.http.Server
import valistrio.core.post.{KafkaDlqSink, KafkaSink}
import valistrio.core.validate.ConfluentSchemaRegistry

object Main extends IOApp {
  override def run(args: List[String]): IO[ExitCode] =
    Slf4jLogger.create[IO].flatMap { implicit logger =>
      Config.make.flatMap { conf =>
        (ConfluentSchemaRegistry.resource(conf.schemaRegistry), KafkaSink.producer(conf.kafka))
          .mapN { (registry, producer) =>
            val sink    = new KafkaSink(producer, conf.kafka.topics.events)
            val dlqSink = new KafkaDlqSink(producer, conf.kafka.topics.dlq)
            new Server(conf.server, registry, sink, dlqSink, logger)
          }
          .use(_.run)
          .as(ExitCode.Success)
      }
    }
}
