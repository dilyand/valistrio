package valistrio

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.apply._
import valistrio.core.Config
import valistrio.core.domain.{FailedEvent, ValidatedEvent}
import valistrio.core.http.Server
import valistrio.core.resources.{ConfluentSchemaRegistry, Kafka, KafkaSink, Logging}

object Main extends IOApp {

  override def run(args: List[String]): IO[ExitCode] =
    Config.make.flatMap(conf => server(conf).use(_.run)).as(ExitCode.Success)

  private def server(conf: Config): Resource[IO, Server] =
    Logging.resource.flatMap { implicit logger =>
      (ConfluentSchemaRegistry.resource(conf.schemaRegistry), Kafka.producer(conf.kafka)).mapN {
        (registry, producer) =>
          val eventSink = new KafkaSink[ValidatedEvent](producer, conf.kafka.topics.events)
          val dlqSink   = new KafkaSink[FailedEvent](producer, conf.kafka.topics.dlq)
          new Server(conf.server, registry, eventSink, dlqSink, logger)
      }
    }
}
