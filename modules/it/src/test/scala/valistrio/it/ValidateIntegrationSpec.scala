package valistrio.it

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.syntax._
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

import scala.io.Source

/** Integration tests for POST /validate against the real shipped Docker image.
  *
  * Container topology:
  *   KafkaContainer (KRaft) → SchemaRegistryContainer → ValistrioContainer
  *
  * Schema setup (beforeAll):
  *  1. Kafka + Schema Registry start.
  *  2. A temporary ConfluentSchemaRegistry client registers the test user schema
  *     (`com.myorg/page_view/1.0.0`) directly via our own algebra — no curl.
  *  3. The Valistrio container starts; its ConfluentSchemaRegistry seeds the
  *     Valistrio-owned schemas (com.valistrio/envelope/1.0.0) on startup.
  *
  * Tests make real HTTP calls to the mapped Valistrio port via [[Http]].
  * Test methods return `IO[MatchResult]` — the `CatsEffect` mixin runs them
  * inside the framework so specs2 can evaluate them correctly.
  */
class ValidateIntegrationSpec
    extends Specification
    with BeforeAfterAll
    with CatsEffect {

  sequential

  override val Timeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(120, "s")

  // ---- Infrastructure ----

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

  // ---- Lifecycle ----

  override def beforeAll(): Unit = {
    kafka.start()
    schemaRegistry.start()

    // Register the test user schema before Valistrio starts.
    // We borrow a ConfluentSchemaRegistry client for just this purpose.
    implicit val logger = Slf4jLogger.getLogger[IO]
    val config = SchemaRegistryConfig(schemaRegistry.url, timeoutMs = 15000)
    ConfluentSchemaRegistry.resource(config).use { reg =>
      reg.register(pageViewSchemaName, pageViewSchemaJson)
    }.unsafeRunSync()

    valistrio.start()
  }

  override def afterAll(): Unit = {
    valistrio.stop()
    schemaRegistry.stop()
    kafka.stop()
    network.close()
  }

  // ---- Helpers ----

  private def validateUri = Uri.unsafeFromString(s"${valistrio.url}/validate")

  private def post(body: String): IO[(Status, Json)] =
    TestHttp.statusAndBody(
      Request[IO](method = Method.POST, uri = validateUri).withEntity(body)
    )

  private def errorTypes(body: Json): List[String] =
    (body \\ "type").flatMap(_.asString)

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000001","produced_at":"2026-06-13T10:00:00Z"}"""

  private def envelope(eventSchema: String, eventData: String, contexts: Option[String] = None): String = {
    val ctxPart = contexts.map(c => s""","contexts":[$c]""").getOrElse("")
    s"""{"schema":"com.valistrio/envelope/1.0.0","data":{"meta":$validMeta,"event":{"schema":"$eventSchema","data":$eventData}$ctxPart}}"""
  }

  private val validEnvelope =
    envelope("com.myorg/page_view/1.0.0", """{"page_url":"https://example.com"}""")

  private val invalidPayloadEnvelope =
    envelope("com.myorg/page_view/1.0.0", """{"not_page_url":"oops"}""")

  private val unknownSchemaEnvelope =
    envelope("com.myorg/not_registered/1.0.0", """{"x":"y"}""")

  private val unknownSchemaName = "com.myorg/not_registered/1.0.0"

  private val multiContextEnvelope =
    envelope(
      "com.myorg/page_view/1.0.0",
      """{"page_url":"https://example.com"}""",
      contexts = Some(
        s"""{"schema":"$unknownSchemaName","data":{"a":"1"}},{"schema":"$unknownSchemaName","data":{"b":"2"}}"""
      )
    )

  // ---- Tests ----

  "POST /validate (containerised)" should {

    "return 200 and valid=true for a valid envelope" in {
      post(validEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.Ok) and
          ((body \\ "valid").headOption must beSome(Json.True))
      }
    }

    "return 400 for malformed JSON" in {
      post("not json {{{").map { case (status, _) =>
        status must beEqualTo(Status.BadRequest)
      }
    }

    "return 400 for structurally invalid envelope" in {
      val bad = """{"schema":"com.valistrio/envelope/1.0.0","data":{},"unexpected":"field"}"""
      post(bad).map { case (status, body) =>
        status must beEqualTo(Status.BadRequest) and
          (errorTypes(body) must contain("structural_decode_error"))
      }
    }

    "return 404 when the event schema is not registered" in {
      post(unknownSchemaEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.NotFound) and
          (errorTypes(body) must contain("schema_not_found"))
      }
    }

    "return 422 when event payload violates the registered schema" in {
      post(invalidPayloadEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableEntity) and
          (errorTypes(body) must contain("schema_validation_failed"))
      }
    }

    "include path and message in schema_validation_failed errors" in {
      post(invalidPayloadEnvelope).map { case (_, body) =>
        val paths    = (body \\ "path").flatMap(_.asString)
        val messages = (body \\ "message").flatMap(_.asString)
        (paths must not(beEmpty)) and (messages must not(beEmpty))
      }
    }

    "mark validation errors as recoverable=true and non-recoverable errors as false" in {
      post(invalidPayloadEnvelope).flatMap { case (_, validBody) =>
        post("not json").map { case (_, invalidBody) =>
          val validFlags   = (validBody   \\ "recoverable").flatMap(_.asBoolean)
          val invalidFlags = (invalidBody \\ "recoverable").flatMap(_.asBoolean)
          (validFlags must contain(true)) and (invalidFlags must contain(false))
        }
      }
    }

    "collect errors from multiple failing contexts without short-circuiting" in {
      post(multiContextEnvelope).map { case (_, body) =>
        // Both contexts reference an unregistered schema → at least 2 schema_not_found entries
        errorTypes(body).count(_ == "schema_not_found") must beGreaterThanOrEqualTo(2)
      }
    }

    "prove the Valistrio-owned envelope schema was seeded at startup" in {
      // If envelope seeding failed, every request would return 404.
      // A 200 from a valid request proves seeding worked.
      post(validEnvelope).map { case (status, _) =>
        status must beEqualTo(Status.Ok)
      }
    }
  }
}
