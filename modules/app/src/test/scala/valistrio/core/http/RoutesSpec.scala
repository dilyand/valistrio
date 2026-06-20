package valistrio.core.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser
import org.http4s._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.SchemaName
import valistrio.core.validate.{SchemaRegistry, ValidateService}

class RoutesSpec extends Specification {

  // ---- Stub ----

  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry[IO] {
    def validate(name: SchemaName, data: Json): IO[Either[ValidateError, Unit]] =
      IO.pure(responses.getOrElse(name.toString, default))
    def register(name: SchemaName, schemaJson: String): IO[Unit] = IO.unit
  }

  // ---- Helpers ----

  private def app(registry: SchemaRegistry[IO] = new StubSchemaRegistry()): HttpApp[IO] =
    Routes.validate(new ValidateService(registry)).orNotFound

  private def post(body: String, registry: SchemaRegistry[IO] = new StubSchemaRegistry()): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = uri"/validate")
      .withEntity(body)
    app(registry).run(req).unsafeRunSync()
  }

  private def bodyJson(resp: Response[IO]): Json =
    parser.parse(resp.as[String].unsafeRunSync()).toOption.get

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validEvent =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""

  private val validEnvelope =
    s"""{"schema":"com.valistrio/envelope/1.0.0","data":{"meta":$validMeta,"event":$validEvent}}"""

  // ---- Tests ----

  "POST /validate" should {

    // -- 200 OK --

    "return 200 with valid=true for a valid envelope" in {
      val resp = post(validEnvelope)
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "valid").headOption must beSome(Json.True)
    }

    // -- 400 Bad Request --

    "return 400 for malformed JSON" in {
      val resp = post("not-json")
      resp.status must beEqualTo(Status.BadRequest)
      val body = bodyJson(resp)
      (body \\ "valid").headOption must beSome(Json.False)
      val types = (body \\ "type").map(_.asString.getOrElse(""))
      types must contain("malformed_json")
    }

    "return 400 for structurally invalid envelope" in {
      val resp = post("""{"schema":"com.valistrio/envelope/1.0.0","data":{},"extra":"field"}""")
      resp.status must beEqualTo(Status.BadRequest)
      val types = (bodyJson(resp) \\ "type").map(_.asString.getOrElse(""))
      types must contain("structural_decode_error")
    }

    // -- 404 Not Found --

    "return 404 when event schema is not in the registry" in {
      val stub = new StubSchemaRegistry(
        responses = Map("com.myorg/page_view/1.0.0" ->
          Left(SchemaNotFound(SchemaName.parse("com.myorg/page_view/1.0.0").toOption.get)))
      )
      val resp = post(validEnvelope, stub)
      resp.status must beEqualTo(Status.NotFound)
      val types = (bodyJson(resp) \\ "type").map(_.asString.getOrElse(""))
      types must contain("schema_not_found")
    }

    // -- 503 Service Unavailable --

    "return 503 when schema registry is unavailable" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryUnavailable("connection refused")))
      post(validEnvelope, stub).status must beEqualTo(Status.ServiceUnavailable)
    }

    // -- 504 Gateway Timeout --

    "return 504 when schema registry times out" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryTimeout))
      post(validEnvelope, stub).status must beEqualTo(Status.GatewayTimeout)
    }

    // -- 422 Unprocessable Entity --

    "return 422 when event data fails schema validation" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val stub = new StubSchemaRegistry(
        responses = Map("com.myorg/page_view/1.0.0" -> Left(err))
      )
      val resp = post(validEnvelope, stub)
      resp.status must beEqualTo(Status.UnprocessableEntity)
      val types = (bodyJson(resp) \\ "type").map(_.asString.getOrElse(""))
      types must contain("schema_validation_failed")
    }

    "include path in validation failure errors" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val stub = new StubSchemaRegistry(
        responses = Map("com.myorg/page_view/1.0.0" -> Left(err))
      )
      val body = bodyJson(post(validEnvelope, stub))
      val paths = (body \\ "path").flatMap(_.asString)
      paths must contain("$.page_url")
    }

    "include recoverable flag in the response error object" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "bad")))
      val stub = new StubSchemaRegistry(
        responses = Map("com.myorg/page_view/1.0.0" -> Left(err))
      )
      val body  = bodyJson(post(validEnvelope, stub))
      val flags = (body \\ "recoverable").flatMap(_.asBoolean)
      flags must contain(true)
    }

    // -- Status priority for mixed errors --

    "prefer 404 over 422 when both schema_not_found and schema_validation_failed are present" in {
      // envelope schema → not found; event schema → validation failure
      val envelopeSubject = "com.valistrio/envelope/1.0.0"
      val eventSubject    = "com.myorg/page_view/1.0.0"
      val stub = new StubSchemaRegistry(responses = Map(
        envelopeSubject -> Left(SchemaNotFound(SchemaName.parse(envelopeSubject).toOption.get)),
        eventSubject    -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.x", "bad"))))
      ))
      post(validEnvelope, stub).status must beEqualTo(Status.NotFound)
    }
  }
}
