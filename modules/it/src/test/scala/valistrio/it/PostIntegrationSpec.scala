package valistrio.it

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.implicits.global
import fs2.kafka._
import io.circe.Json
import io.circe.parser
import org.apache.kafka.clients.admin.NewTopic
import org.http4s._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.Network
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config.SchemaRegistryConfig
import valistrio.core.domain.SchemaRef
import valistrio.core.validate.ConfluentSchemaRegistry
import valistrio.it.containers.{KafkaContainer, SchemaRegistryContainer, ValistrioContainer}

import java.util.UUID
import scala.concurrent.duration._
import scala.io.Source

/** End-to-end integration tests for POST /post against the real shipped Docker image.
  *
  * Same container topology as [[ValidateIntegrationSpec]] (Kafka → Schema Registry →
  * Valistrio), with one addition: the `valistrio.events` topic must be created before
  * Valistrio starts, since the test Kafka broker runs with auto-topic-creation disabled.
  *
  * Tests run `sequential` and the "registry unavailable" case stops the Schema Registry
  * container — it is deliberately ordered last so it doesn't affect earlier assertions.
  */
class PostIntegrationSpec
    extends Specification
    with BeforeAfterAll
    with CatsEffect {

  sequential

  override val Timeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  // ---- Infrastructure ----

  private val Topic    = "valistrio.events"
  private val DlqTopic = "valistrio.dlq"

  private val network        = Network.newNetwork()
  private val kafka          = new KafkaContainer(network)
  private val schemaRegistry = new SchemaRegistryContainer(network, kafka)
  private val valistrio      = new ValistrioContainer(network, schemaRegistry.internalUrl, kafka.internalBootstrap)

  // ---- Test schema ----

  private val pageViewSchemaName =
    SchemaRef.parse("com.myorg/page_view/1.0.0")
      .getOrElse(throw new IllegalStateException("invalid schema name"))

  private val pageViewSchemaJson =
    Source.fromResource("schemas/page_view-1.0.0.json").mkString

  // A second schema, registered but never queried until the "registry unavailable" test.
  // CachedSchemaRegistryClient caches "latest" lookups per subject (confirmed via 7.7.x's
  // `latestVersionCache`) — querying page_view or the envelope schema again would hit the
  // cache and return success even with the registry stopped, so this test needs a subject
  // whose "latest" has never been resolved before.
  private val freshSchemaName =
    SchemaRef.parse("com.myorg/click_event/1.0.0")
      .getOrElse(throw new IllegalStateException("invalid schema name"))

  private val freshSchemaJson =
    """{
      |  "$schema": "http://json-schema.org/draft-07/schema#",
      |  "title": "click_event-1.0.0",
      |  "type": "object",
      |  "properties": { "x": { "type": "string" } },
      |  "required": ["x"],
      |  "additionalProperties": false
      |}""".stripMargin

  // ---- Lifecycle ----

  override def beforeAll(): Unit = {
    kafka.start()
    createTopics().unsafeRunSync()
    schemaRegistry.start()

    implicit val logger = Slf4jLogger.getLogger[IO]
    val config = SchemaRegistryConfig(schemaRegistry.url, timeoutMs = 15000, cacheCapacity = 2000)
    ConfluentSchemaRegistry.resource(config).use { reg =>
      for {
        _ <- reg.register(pageViewSchemaName, pageViewSchemaJson)
        _ <- reg.register(freshSchemaName, freshSchemaJson)
      } yield ()
    }.unsafeRunSync()

    valistrio.start()
  }

  override def afterAll(): Unit = {
    valistrio.stop()
    if (schemaRegistry.container.isRunning) schemaRegistry.stop()
    kafka.stop()
    network.close()
  }

  private def createTopics(): IO[Unit] =
    KafkaAdminClient
      .resource[IO](AdminClientSettings(kafka.externalBootstrap))
      .use { admin =>
        admin.createTopic(new NewTopic(Topic, 1, 1.toShort)) >>
          admin.createTopic(new NewTopic(DlqTopic, 1, 1.toShort))
      }

  // ---- Helpers ----

  private def postUri = Uri.unsafeFromString(s"${valistrio.url}/post")

  private def post(body: String): IO[(Status, Json)] =
    TestHttp.statusAndBody(
      Request[IO](method = Method.POST, uri = postUri).withEntity(body)
    )

  /** Reads whatever is currently on the topic within `window`, from the beginning,
    * using a fresh consumer group each call so repeated calls don't miss messages
    * already consumed by an earlier call in the same test run.
    */
  private def messagesOnTopic(topic: String, window: FiniteDuration = 5.seconds): IO[List[Json]] = {
    val settings = ConsumerSettings[IO, String, String]
      .withBootstrapServers(kafka.externalBootstrap)
      .withGroupId(s"valistrio-it-post-${UUID.randomUUID()}")
      .withAutoOffsetReset(AutoOffsetReset.Earliest)

    KafkaConsumer.resource(settings).use { consumer =>
      consumer.subscribeTo(topic) >>
        consumer.stream.map(_.record.value).interruptAfter(window).compile.toList
    }.map(_.flatMap(parser.parse(_).toOption))
  }

  private def eventIdsOnTopic(window: FiniteDuration = 5.seconds): IO[List[String]] =
    messagesOnTopic(Topic, window).map(_.flatMap(j => (j \\ "event_id").flatMap(_.asString)))

  // ---- Fixtures ----

  private def envelope(eventId: String, bodyData: String = """{"page_url":"https://example.com"}"""): String =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"$eventId","produced_at":"2026-06-13T10:00:00Z"},"body":{"schema":"com.myorg/page_view/1.0.0","data":$bodyData}}}"""

  // ---- Tests ----

  "POST /post (containerised)" should {

    "return 200 accepted with written=events and write the message to the configured Kafka topic" in {
      val eventId = "018f1e2a-dead-beef-cafe-000000000010"
      post(envelope(eventId)).flatMap { case (status, body) =>
        eventIdsOnTopic().map { ids =>
          (status must beEqualTo(Status.Ok)) and
            ((body \\ "accepted").headOption must beSome(Json.True)) and
            ((body \\ "written").flatMap(_.asString) must contain("events")) and
            (ids must contain(eventId))
        }
      }
    }

    "write a message for each repeated event_id — deduplication is not enforced in 0.1.0" in {
      val eventId = "018f1e2a-dead-beef-cafe-000000000011"
      for {
        first  <- post(envelope(eventId))
        second <- post(envelope(eventId))
        ids    <- eventIdsOnTopic()
      } yield {
        (first._1 must beEqualTo(Status.Ok)) and
          (second._1 must beEqualTo(Status.Ok)) and
          (ids.count(_ == eventId) must beEqualTo(2))
      }
    }

    "return 200 accepted with written=dlq and salvage the failed event to the DLQ topic when the payload fails schema validation" in {
      val eventId = "018f1e2a-dead-beef-cafe-000000000012"
      val invalidPayload = envelope(eventId, """{"not_page_url":"oops"}""")
      post(invalidPayload).flatMap { case (status, body) =>
        for {
          eventIds <- eventIdsOnTopic()
          dlq      <- messagesOnTopic(DlqTopic)
        } yield {
          val originals = dlq.flatMap(r => (r \\ "original").headOption)
          (status must beEqualTo(Status.Ok)) and
            ((body \\ "accepted").headOption must beSome(Json.True)) and
            ((body \\ "written").flatMap(_.asString) must contain("dlq")) and
            ((body \\ "type").flatMap(_.asString) must contain("schema_validation_failed")) and
            (eventIds must not(contain(eventId))) and
            (originals.flatMap(o => (o \\ "event_id").flatMap(_.asString)) must contain(eventId)) and
            (dlq.flatMap(r => (r \\ "type").flatMap(_.asString)) must contain("schema_validation_failed"))
        }
      }
    }

    "return 503 when the schema registry is unreachable" in {
      // Ordered last: stops Schema Registry, which subsequent tests would otherwise need.
      // Uses freshSchemaName, never queried before this point — page_view's and the
      // envelope schema's "latest" lookups are already cached from earlier tests in this
      // spec, so they'd return success even with the registry down.
      schemaRegistry.stop()
      val eventId = "018f1e2a-dead-beef-cafe-000000000013"
      val body =
        s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"$eventId","produced_at":"2026-06-13T10:00:00Z"},"body":{"schema":"${freshSchemaName.toString}","data":{"x":"y"}}}}"""
      post(body).map { case (status, respBody) =>
        (status must beEqualTo(Status.ServiceUnavailable)) and
          ((respBody \\ "accepted").headOption must beSome(Json.False)) and
          ((respBody \\ "type").flatMap(_.asString) must contain("schema_registry_unavailable"))
      }
    }
  }
}
