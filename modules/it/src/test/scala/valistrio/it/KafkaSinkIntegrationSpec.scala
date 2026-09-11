package valistrio.it

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
import valistrio.core.domain.{Event, ValidatedEvent}
import valistrio.core.post.KafkaSink
import valistrio.it.containers.KafkaContainer

import scala.concurrent.duration._

/** Integration test for [[KafkaSink]] against a real Kafka broker, exercising the sink
  * algebra directly in the test JVM.
  */
class KafkaSinkIntegrationSpec extends Specification with BeforeAfterAll with CatsEffect {

  sequential

  override val Timeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  private val Topic = "valistrio.events.it"

  private val network = Network.newNetwork()
  private val kafka    = new KafkaContainer(network)

  private def config = KafkaConfig(kafka.externalBootstrap, KafkaTopics(Topic, "valistrio.dlq.it"))

  override def beforeAll(): Unit = {
    kafka.start()
    createTopic().unsafeRunSync()
  }

  override def afterAll(): Unit = {
    kafka.stop()
    network.close()
  }

  private def createTopic(): IO[Unit] =
    KafkaAdminClient
      .resource[IO](AdminClientSettings(kafka.externalBootstrap))
      .use(_.createTopic(new NewTopic(Topic, 1, 1.toShort)))

  private def consumeOne: IO[String] = {
    val settings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(kafka.externalBootstrap)
      .withGroupId("valistrio-it-kafka-sink")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer.resource(settings).use { consumer =>
      consumer.subscribeTo(Topic) >>
        consumer.stream.take(1).map(_.record.value).compile.lastOrError.timeout(30.seconds)
    }
  }

  private val eventJson =
    """{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"018f1e2a-dead-beef-cafe-000000000002","produced_at":"2026-06-13T10:00:00Z"},"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}}}"""
  private val json  = parser.parse(eventJson).toOption.get
  private val event = ValidatedEvent.of(Event.fromJson(json).toOption.get).toOption.get

  "KafkaSink" should {
    "write a validated event so the original JSON can be read back from the topic" in {
      KafkaSink.resource(config).use { sink =>
        for {
          _        <- sink.write(event)
          consumed <- consumeOne
        } yield consumed
      }.map { consumed =>
        parser.parse(consumed).toOption must beSome(json)
      }
    }
  }
}
