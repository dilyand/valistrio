package valistrio.it

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.implicits.global
import fs2.kafka._
import io.circe.parser
import org.apache.kafka.clients.admin.NewTopic
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.Network
import valistrio.core.Config.{KafkaConfig, KafkaTopics}
import valistrio.core.domain.{Event, FailedEvent, ResponseError, ValidatedEvent}
import valistrio.core.resources.{Kafka, KafkaSink}
import valistrio.it.containers.KafkaContainer

import java.time.Instant
import scala.concurrent.duration._

/** Integration test for the events and DLQ [[KafkaSink]]s against a real Kafka broker,
  * exercising the sink algebra directly in the test JVM through one shared producer.
  */
class KafkaSinkIntegrationSpec extends Specification with BeforeAfterAll with CatsEffect {

  sequential

  override val Timeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  private val EventsTopic = "valistrio.events.it"
  private val DlqTopic    = "valistrio.dlq.it"
  private val MaxBytes    = 2097152L

  private val network = Network.newNetwork()
  private val kafka    = new KafkaContainer(network)

  private def config = KafkaConfig(kafka.externalBootstrap, KafkaTopics(EventsTopic, DlqTopic))

  override def beforeAll(): Unit = {
    kafka.start()
    createTopics().unsafeRunSync()
  }

  override def afterAll(): Unit = {
    kafka.stop()
    network.close()
  }

  private def createTopics(): IO[Unit] =
    KafkaAdminClient
      .resource[IO](AdminClientSettings(kafka.externalBootstrap))
      .use { admin =>
        admin.createTopic(new NewTopic(EventsTopic, 1, 1.toShort)) >>
          admin.createTopic(new NewTopic(DlqTopic, 1, 1.toShort))
      }

  private def consumeOne(topic: String): IO[String] = {
    val settings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(kafka.externalBootstrap)
      .withGroupId(s"valistrio-it-kafka-sink-$topic")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer.resource(settings).use { consumer =>
      consumer.subscribeTo(topic) >>
        consumer.stream.take(1).map(_.record.value).compile.lastOrError.timeout(30.seconds)
    }
  }

  private val eventJson =
    """{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"018f1e2a-dead-beef-cafe-000000000002","produced_at":"2026-06-13T10:00:00Z"},"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}}}"""
  private val json  = parser.parse(eventJson).toOption.get
  private val event = ValidatedEvent.of(Event.fromJson(json).toOption.get).toOption.get

  private val failed = FailedEvent.of(
    json,
    NonEmptyList.one(ResponseError("schema_validation_failed", recoverable = true, Some("$.page_url"), "must be a string")),
    Instant.parse("2026-06-13T10:00:00Z"),
    MaxBytes
  )

  "KafkaSink" should {
    "write a validated event so the original JSON can be read back from the topic" in {
      Kafka.producer(config).use { producer =>
        val sink = new KafkaSink[ValidatedEvent](producer, EventsTopic)
        for {
          _        <- sink.write(event)
          consumed <- consumeOne(EventsTopic)
        } yield consumed
      }.map { consumed =>
        parser.parse(consumed).toOption must beSome(json)
      }
    }
  }

  "The DLQ KafkaSink" should {
    "write a FailedEvent so its original and errors can be read back from the DLQ topic" in {
      Kafka.producer(config).use { producer =>
        val dlq = new KafkaSink[FailedEvent](producer, DlqTopic)
        for {
          _        <- dlq.write(failed)
          consumed <- consumeOne(DlqTopic)
        } yield consumed
      }.map { consumed =>
        val record = parser.parse(consumed).toOption.get
        ((record \\ "original").headOption must beSome(json)) and
          ((record \\ "type").flatMap(_.asString) must contain("schema_validation_failed")) and
          ((record \\ "failed_at").flatMap(_.asString) must contain("2026-06-13T10:00:00Z"))
      }
    }
  }
}
