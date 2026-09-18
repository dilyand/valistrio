package valistrio.it

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.implicits.global
import fs2.kafka._
import io.circe.Json
import io.circe.parser
import io.circe.syntax._
import org.apache.kafka.clients.admin.NewTopic
import org.http4s._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll
import org.testcontainers.containers.Network
import org.typelevel.ci._
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config.SchemaRegistryConfig
import valistrio.core.domain.SchemaRef
import valistrio.core.resources.schemas.ConfluentSchemaRegistry
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

  private def postV1(eventType: String, body: String): IO[Status] =
    TestHttp.status(
      Request[IO](method = Method.POST, uri = Uri.unsafeFromString(s"${valistrio.url}/v1/$eventType")).withEntity(body)
    )

  /** A CORS preflight for an adapter path: OPTIONS with an Origin and the requested method. */
  private def preflight(uri: Uri): IO[(Status, Option[String])] =
    TestHttp.client.use { c =>
      val req = Request[IO](method = Method.OPTIONS, uri = uri)
        .putHeaders(
          org.http4s.Header.Raw(ci"Origin", "https://demo.example"),
          org.http4s.Header.Raw(ci"Access-Control-Request-Method", "POST")
        )
      c.run(req).use(resp => IO.pure((resp.status, resp.headers.get(ci"Access-Control-Allow-Origin").map(_.head.value))))
    }

  private def postTp2(body: String): IO[Status] =
    TestHttp.status(
      Request[IO](method = Method.POST, uri = tp2Uri).withEntity(body)
    )

  private def tp2Uri = Uri.unsafeFromString(s"${valistrio.url}/com.snowplowanalytics.snowplow/tp2")

  /** A tp2 `payload_data` envelope wrapping the given events. */
  private def tp2(events: Json*): String =
    Json.obj(
      "schema" -> "iglu:com.snowplowanalytics.snowplow/payload_data/jsonschema/1-0-4".asJson,
      "data"   -> Json.fromValues(events)
    ).noSpaces

  /** One tp2 self-describing event carrying `innerSchema`/`innerData` inside `ue_pr`. */
  private def ueEvent(eventId: String, innerSchema: String, innerData: Json): Json = {
    val uePr = Json.obj(
      "schema" -> "iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0".asJson,
      "data"   -> Json.obj("schema" -> innerSchema.asJson, "data" -> innerData)
    ).noSpaces
    Json.obj("e" -> "ue".asJson, "eid" -> eventId.asJson, "dtm" -> "1750000000000".asJson, "ue_pr" -> uePr.asJson)
  }

  /** Reads whatever is currently on the topic within `window`, from the beginning,
    * using a fresh consumer group each call so repeated calls don't miss messages
    * already consumed by an earlier call in the same test run.
    */
  private def messagesOnTopic(topic: String, window: FiniteDuration = 5.seconds): IO[List[Json]] = {
    // Key as Option[String] so a null key (an unkeyed DLQ record from a malformed body) reads as
    // None rather than tripping the String deserializer; only the value is used here.
    val settings = ConsumerSettings[IO, Option[String], String]
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

  "POST /v1/track (RudderStack adapter, containerised)" should {

    "map a RudderStack track event to the event document and write it to the events topic" in {
      // The producer carries the body schema ref in properties.schema; the adapter strips it, leaving
      // page_url as the body data (matching the registered com.myorg/page_view/1.0.0 schema), and uses
      // messageId as the event_id. This exercises the built document against the real event schema.
      val eventId = "018f1e2a-dead-beef-cafe-000000000020"
      val wire =
        s"""{"type":"track","event":"page_view","properties":{"page_url":"https://example.com","schema":"com.myorg/page_view/1.0.0"},"messageId":"$eventId","originalTimestamp":"2026-06-13T10:00:00Z"}"""
      postV1("track", wire).flatMap { status =>
        eventIdsOnTopic().map { ids =>
          (status must beEqualTo(Status.Ok)) and (ids must contain(eventId))
        }
      }
    }

    "return 400 when the request cannot be mapped (no producer schema ref), writing nothing" in {
      val eventId = "018f1e2a-dead-beef-cafe-000000000021"
      val wire    = s"""{"type":"track","properties":{"page_url":"https://example.com"},"messageId":"$eventId"}"""
      postV1("track", wire).flatMap { status =>
        eventIdsOnTopic().map(ids => (status must beEqualTo(Status.BadRequest)) and (ids must not(contain(eventId))))
      }
    }

    "return 404 for an unsupported /v1 type" in {
      postV1("identify", "{}").map(_ must beEqualTo(Status.NotFound))
    }

    "answer the CORS preflight with an allow-origin header" in {
      preflight(Uri.unsafeFromString(s"${valistrio.url}/v1/track")).map { case (status, allowOrigin) =>
        (status must beEqualTo(Status.Ok)) and (allowOrigin must beSome("*"))
      }
    }
  }

  "POST /com.snowplowanalytics.snowplow/tp2 (Snowplow adapter, containerised)" should {

    "map a Snowplow self-describing event to the event document and write it to the events topic" in {
      // The producer packs the body schema and data inside ue_pr as a self-describing event; the
      // adapter translates the iglu ref to com.myorg/page_view/1.0.0, forwards page_url as the body
      // data (matching the registered schema), and uses eid as the event_id.
      val eventId = "018f1e2a-dead-beef-cafe-000000000030"
      val body =
        tp2(ueEvent(eventId, "iglu:com.myorg/page_view/jsonschema/1-0-0", Json.obj("page_url" -> "https://example.com".asJson)))
      postTp2(body).flatMap { status =>
        eventIdsOnTopic().map(ids => (status must beEqualTo(Status.Ok)) and (ids must contain(eventId)))
      }
    }

    "return 400 for a batch of more than one event, writing nothing" in {
      val eventId = "018f1e2a-dead-beef-cafe-000000000031"
      val inner   = Json.obj("page_url" -> "https://example.com".asJson)
      val body = tp2(
        ueEvent(eventId, "iglu:com.myorg/page_view/jsonschema/1-0-0", inner),
        ueEvent("018f1e2a-dead-beef-cafe-000000000032", "iglu:com.myorg/page_view/jsonschema/1-0-0", inner)
      )
      postTp2(body).flatMap { status =>
        eventIdsOnTopic().map(ids => (status must beEqualTo(Status.BadRequest)) and (ids must not(contain(eventId))))
      }
    }

    "return 400 when the request body cannot be decoded" in {
      postTp2("this is not a payload_data envelope").map(_ must beEqualTo(Status.BadRequest))
    }

    "answer the CORS preflight with an allow-origin header" in {
      preflight(tp2Uri).map { case (status, allowOrigin) =>
        (status must beEqualTo(Status.Ok)) and (allowOrigin must beSome("*"))
      }
    }
  }

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

    "return 200 accepted with written=dlq for malformed JSON, salvaging the raw body to the DLQ unkeyed" in {
      // Regression: a malformed body has no parseable event_id, so the DLQ record is written with a
      // null key. This must land as an owned failure (200), not NPE on the null key.
      val malformed = "this is not json"
      post(malformed).flatMap { case (status, body) =>
        messagesOnTopic(DlqTopic).map { dlq =>
          (status must beEqualTo(Status.Ok)) and
            ((body \\ "accepted").headOption must beSome(Json.True)) and
            ((body \\ "written").flatMap(_.asString) must contain("dlq")) and
            ((body \\ "type").flatMap(_.asString) must contain("malformed_json")) and
            (dlq.flatMap(r => (r \\ "original").headOption) must contain(Json.fromString(malformed)))
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
