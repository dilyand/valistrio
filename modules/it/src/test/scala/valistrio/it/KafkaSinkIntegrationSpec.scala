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
import valistrio.core.Config.KafkaConfig
import valistrio.core.domain._
import valistrio.core.post.KafkaSink
import valistrio.it.containers.KafkaContainer

import scala.concurrent.duration._

/** Integration test for [[KafkaSink]] against a real Kafka broker.
  *
  * Unlike [[ValidateIntegrationSpec]], this runs the sink directly in the test
  * JVM rather than inside the shipped Docker image — there is no HTTP surface
  * for /post yet (see issue #13/#14), so the algebra is exercised directly.
  */
class KafkaSinkIntegrationSpec extends Specification with BeforeAfterAll with CatsEffect {

  sequential

  override val Timeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  private val Topic = "valistrio.events.it"

  private val network = Network.newNetwork()
  private val kafka    = new KafkaContainer(network)

  private def config = KafkaConfig(kafka.externalBootstrap, Topic)

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

  private val envelope = Event(
    SchemaRef("com.valistrio", "envelope", SchemaVersion(1, 0, 0)),
    EventData(
      EventMeta("018f1e2a-dead-beef-cafe-000000000002", "2026-06-13T10:00:00Z"),
      TypedData(
        SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0)),
        io.circe.Json.obj("page_url" -> io.circe.Json.fromString("https://example.com"))
      ),
      None: Option[NonEmptyList[TypedData]]
    )
  )

  "KafkaSink" should {
    "write a validated envelope so it can be read back from the configured topic" in {
      KafkaSink.resource(config).use { sink =>
        for {
          result   <- sink.write(envelope)
          consumed <- consumeOne
        } yield result -> consumed
      }.map { case (result, consumed) =>
        (result must beRight(())) and
          (parser.decode[Event](consumed) must beRight(envelope))
      }
    }
  }
}
