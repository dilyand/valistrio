package valistrio.it

import cats.effect.IO
import cats.effect.testing.specs2.CatsEffect
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s._
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
  * Container topology: KafkaContainer (KRaft) → SchemaRegistryContainer → ValistrioContainer.
  * beforeAll registers the test user schema (`com.myorg/page_view/1.0.0`); the Valistrio
  * container seeds its own `io.github.dilyand.valistrio/event/1.0.0` on startup.
  *
  * Structural validation is the event schema's job now, so unknown-field / missing-field /
  * empty-contexts cases show up here as `schema_validation_failed` (422).
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
    SchemaRef.parse("com.myorg/page_view/1.0.0").getOrElse(throw new IllegalStateException("invalid schema name"))

  private val pageViewSchemaJson =
    Source.fromResource("schemas/page_view-1.0.0.json").mkString

  // ---- Lifecycle ----

  override def beforeAll(): Unit = {
    kafka.start()
    schemaRegistry.start()

    implicit val logger = Slf4jLogger.getLogger[IO]
    val config = SchemaRegistryConfig(schemaRegistry.url, timeoutMs = 15000, cacheCapacity = 2000)
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
    TestHttp.statusAndBody(Request[IO](method = Method.POST, uri = validateUri).withEntity(body))

  private def errorTypes(body: Json): List[String] =
    (body \\ "type").flatMap(_.asString)

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000001","produced_at":"2026-06-13T10:00:00Z"}"""

  private def envelope(bodySchema: String, bodyData: String, contexts: Option[String] = None): String = {
    val ctxPart = contexts.map(c => s""","contexts":[$c]""").getOrElse("")
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":{"schema":"$bodySchema","data":$bodyData}$ctxPart}}"""
  }

  private val validEnvelope =
    envelope("com.myorg/page_view/1.0.0", """{"page_url":"https://example.com"}""")

  private val invalidPayloadEnvelope =
    envelope("com.myorg/page_view/1.0.0", """{"not_page_url":"oops"}""")

  private val unknownSchemaName = "com.myorg/not_registered/1.0.0"
  private val unknownSchemaEnvelope =
    envelope(unknownSchemaName, """{"x":"y"}""")

  private val multiContextEnvelope =
    envelope(
      "com.myorg/page_view/1.0.0",
      """{"page_url":"https://example.com"}""",
      contexts = Some(s"""{"schema":"$unknownSchemaName","data":{"a":"1"}},{"schema":"$unknownSchemaName","data":{"b":"2"}}""")
    )

  // ---- Tests ----

  "POST /validate (containerised)" should {

    "return 200 and valid=true for a valid envelope" in {
      post(validEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.Ok) and ((body \\ "valid").headOption must beSome(Json.True))
      }
    }

    "return 400 for malformed JSON" in {
      post("not json {{{").map { case (status, _) => status must beEqualTo(Status.BadRequest) }
    }

    // -- Structural violations are now schema validation failures (422) --

    "return 422 for an unknown field at the envelope level" in {
      val bad = s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}},"unexpected":"field"}"""
      post(bad).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableContent) and (errorTypes(body) must contain("schema_validation_failed"))
      }
    }

    "return 422 when the event body is missing" in {
      val bad = s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta}}"""
      post(bad).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableContent) and (errorTypes(body) must contain("schema_validation_failed"))
      }
    }

    "return 422 for an empty contexts array" in {
      val bad = s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}},"contexts":[]}}"""
      post(bad).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableContent) and (errorTypes(body) must contain("schema_validation_failed"))
      }
    }

    "return 422 when the body schema is not registered" in {
      post(unknownSchemaEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableContent) and (errorTypes(body) must contain("schema_not_found"))
      }
    }

    "return 422 when the payload violates its registered schema" in {
      post(invalidPayloadEnvelope).map { case (status, body) =>
        status must beEqualTo(Status.UnprocessableContent) and (errorTypes(body) must contain("schema_validation_failed"))
      }
    }

    "include path and message in schema_validation_failed errors" in {
      post(invalidPayloadEnvelope).map { case (_, body) =>
        val paths    = (body \\ "path").flatMap(_.asString)
        val messages = (body \\ "message").flatMap(_.asString)
        (paths must not(beEmpty)) and (messages must not(beEmpty))
      }
    }

    "mark validation errors recoverable=true and malformed JSON as false" in {
      post(invalidPayloadEnvelope).flatMap { case (_, validBody) =>
        post("not json").map { case (_, invalidBody) =>
          val validFlags   = (validBody \\ "recoverable").flatMap(_.asBoolean)
          val invalidFlags = (invalidBody \\ "recoverable").flatMap(_.asBoolean)
          (validFlags must contain(true)) and (invalidFlags must contain(false))
        }
      }
    }

    "collect errors from multiple failing contexts without short-circuiting" in {
      post(multiContextEnvelope).map { case (_, body) =>
        errorTypes(body).count(_ == "schema_not_found") must beGreaterThanOrEqualTo(2)
      }
    }

    "prove the Valistrio-owned event schema was seeded at startup" in {
      post(validEnvelope).map { case (status, _) => status must beEqualTo(Status.Ok) }
    }
  }
}
