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
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.SchemaRef
import valistrio.core.post.{PostService, Sink, StubSink}
import valistrio.core.validate.{SchemaRegistry, ValidateService}

class RoutesSpec extends Specification {

  // ---- Stub ----

  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry {
    def validate(ref: SchemaRef, data: Json): IO[Unit] =
      IO.fromEither(responses.getOrElse(ref.toString, default))
    def register(ref: SchemaRef, schemaJson: String): IO[Unit] = IO.unit
  }

  private def notFound(subject: String): Either[ValidateError, Unit] =
    Left(SchemaNotFound(SchemaRef.parse(subject).toOption.get))

  // ---- Helpers ----

  private def app(registry: SchemaRegistry = new StubSchemaRegistry()): HttpApp[IO] =
    Routes.validate(new ValidateService(registry)).orNotFound

  private def post(body: String, registry: SchemaRegistry = new StubSchemaRegistry()): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = uri"/validate").withEntity(body)
    app(registry).run(req).unsafeRunSync()
  }

  private def bodyJson(resp: Response[IO]): Json =
    parser.parse(resp.as[String].unsafeRunSync()).toOption.get

  private def postApp(registry: SchemaRegistry, sink: Sink): HttpApp[IO] =
    Routes.post(new PostService(new ValidateService(registry), sink)).orNotFound

  private def postEnvelope(
    body: String,
    registry: SchemaRegistry = new StubSchemaRegistry(),
    sink: Sink = StubSink.succeeding.unsafeRunSync()
  ): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = uri"/post").withEntity(body)
    postApp(registry, sink).run(req).unsafeRunSync()
  }

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validBody =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""
  private val validContext =
    """{"schema":"com.myorg/user/1.0.0","data":{"user_id":"u-123"}}"""

  private val validEnvelope =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":$validBody}}"""
  private val validEnvelopeWithContext =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":$validBody,"contexts":[$validContext]}}"""

  private val bodySubject    = "com.myorg/page_view/1.0.0"
  private val contextSubject = "com.myorg/user/1.0.0"

  // ---- Tests ----

  "POST /validate" should {

    "return 200 with valid=true for a valid envelope" in {
      val resp = post(validEnvelope)
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "valid").headOption must beSome(Json.True)
    }

    "return 400 for malformed JSON" in {
      val resp = post("not-json")
      resp.status must beEqualTo(Status.BadRequest)
      (bodyJson(resp) \\ "valid").headOption must beSome(Json.False)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("malformed_json")
    }

    "return 422 when the body schema is not registered" in {
      val resp = post(validEnvelope, new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject))))
      resp.status must beEqualTo(Status.UnprocessableContent)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("schema_not_found")
    }

    "return 503 when schema registry is unavailable" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryUnavailable("connection refused")))
      post(validEnvelope, stub).status must beEqualTo(Status.ServiceUnavailable)
    }

    "return 504 when schema registry times out" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryTimeout))
      post(validEnvelope, stub).status must beEqualTo(Status.GatewayTimeout)
    }

    "return 422 when the body fails schema validation" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val resp = post(validEnvelope, new StubSchemaRegistry(responses = Map(bodySubject -> Left(err))))
      resp.status must beEqualTo(Status.UnprocessableContent)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("schema_validation_failed")
    }

    "include path in validation failure errors" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val body = bodyJson(post(validEnvelope, new StubSchemaRegistry(responses = Map(bodySubject -> Left(err)))))
      (body \\ "path").flatMap(_.asString) must contain("$.page_url")
    }

    "include the recoverable flag in the response error object" in {
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "bad")))
      val body = bodyJson(post(validEnvelope, new StubSchemaRegistry(responses = Map(bodySubject -> Left(err)))))
      (body \\ "recoverable").flatMap(_.asBoolean) must contain(true)
    }

    "return 422 when a referenced schema is missing, collected alongside a validation failure" in {
      val stub = new StubSchemaRegistry(responses = Map(
        bodySubject    -> notFound(bodySubject),
        contextSubject -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.x", "bad"))))
      ))
      post(validEnvelopeWithContext, stub).status must beEqualTo(Status.UnprocessableContent)
    }
  }

  "POST /post" should {

    "return 200 with written=true and write the event when validation succeeds" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      val resp = postEnvelope(validEnvelope, sink = sink)
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "written").headOption must beSome(Json.True)
      sink.written.unsafeRunSync() must haveSize(1)
    }

    "return 400 for malformed JSON" in {
      val resp = postEnvelope("not-json")
      resp.status must beEqualTo(Status.BadRequest)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("malformed_json")
    }

    "return 422 when the body schema is not registered" in {
      val stub = new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject)))
      postEnvelope(validEnvelope, registry = stub).status must beEqualTo(Status.UnprocessableContent)
    }

    "return 503 when the sink is unreachable" in {
      val sink = StubSink.failingWith(Unavailable("connection refused")).unsafeRunSync()
      val resp = postEnvelope(validEnvelope, sink = sink)
      resp.status must beEqualTo(Status.ServiceUnavailable)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("sink_unavailable")
    }

    "return 504 when the sink times out" in {
      val sink = StubSink.failingWith(Timeout).unsafeRunSync()
      postEnvelope(validEnvelope, sink = sink).status must beEqualTo(Status.GatewayTimeout)
    }

    "return 502 when the sink write fails for an unmapped reason" in {
      val sink = StubSink.failingWith(WriteFailed("disk full")).unsafeRunSync()
      val resp = postEnvelope(validEnvelope, sink = sink)
      resp.status must beEqualTo(Status.BadGateway)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("sink_write_failed")
    }

    "not write to the sink when validation fails" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      val stub = new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject)))
      postEnvelope(validEnvelope, registry = stub, sink = sink)
      sink.written.unsafeRunSync() must beEmpty
    }
  }
}
