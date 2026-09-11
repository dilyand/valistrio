package valistrio.core.http

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser
import org.http4s._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.{SinkError, ValidateError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.{FailedEvent, SchemaRef, ValidatedEvent}
import valistrio.core.post.PostService
import valistrio.core.resources.{SchemaRegistry, Sink}
import valistrio.core.validate.ValidateService

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

  /** Sink doubles that succeed silently or raise a configured SinkError; neither records. */
  private def okSink: Sink[ValidatedEvent]                    = _ => IO.unit
  private def failingSink(e: SinkError): Sink[ValidatedEvent] = _ => IO.raiseError(e)
  private def okDlq: Sink[FailedEvent]                        = _ => IO.unit
  private def failingDlq(e: SinkError): Sink[FailedEvent]     = _ => IO.raiseError(e)

  /** A recording events sink so tests can assert whether a write reached the events topic. */
  private final class RecordingSink extends Sink[ValidatedEvent] {
    private val store                          = scala.collection.mutable.ArrayBuffer.empty[ValidatedEvent]
    def write(event: ValidatedEvent): IO[Unit] = IO { store += event; () }
    def count: Int                             = store.size
  }

  // ---- Helpers ----

  private def app(registry: SchemaRegistry = new StubSchemaRegistry()): HttpApp[IO] =
    Routes.validate(new ValidateService(registry)).orNotFound

  private def post(body: String, registry: SchemaRegistry = new StubSchemaRegistry()): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = uri"/validate").withEntity(body)
    app(registry).run(req).unsafeRunSync()
  }

  private def bodyJson(resp: Response[IO]): Json =
    parser.parse(resp.as[String].unsafeRunSync()).toOption.get

  private val MaxBytes = 2097152L

  private def postApp(registry: SchemaRegistry, sink: Sink[ValidatedEvent], dlqSink: Sink[FailedEvent]): HttpApp[IO] =
    Routes.post(new PostService(new ValidateService(registry), sink, dlqSink, MaxBytes)).orNotFound

  private def postEnvelope(
    body: String,
    registry: SchemaRegistry = new StubSchemaRegistry(),
    sink: Sink[ValidatedEvent] = okSink,
    dlqSink: Sink[FailedEvent] = okDlq
  ): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = uri"/post").withEntity(body)
    postApp(registry, sink, dlqSink).run(req).unsafeRunSync()
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

    "return 200 accepted with written=events and write the event when validation succeeds" in {
      val sink = new RecordingSink
      val resp = postEnvelope(validEnvelope, sink = sink)
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "accepted").headOption must beSome(Json.True)
      (bodyJson(resp) \\ "written").flatMap(_.asString) must contain("events")
      sink.count must beEqualTo(1)
    }

    "return 200 accepted with written=dlq for malformed JSON (owned, salvaged to the DLQ)" in {
      val resp = postEnvelope("not-json")
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "accepted").headOption must beSome(Json.True)
      (bodyJson(resp) \\ "written").flatMap(_.asString) must contain("dlq")
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("malformed_json")
    }

    "return 200 accepted with written=dlq, without writing to the events sink, when the body schema is not registered" in {
      val sink = new RecordingSink
      val stub = new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject)))
      val resp = postEnvelope(validEnvelope, registry = stub, sink = sink)
      resp.status must beEqualTo(Status.Ok)
      (bodyJson(resp) \\ "written").flatMap(_.asString) must contain("dlq")
      sink.count must beEqualTo(0)
    }

    "return 503 accepted=false when the registry is unavailable (Retry, not DLQ'd)" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryUnavailable("connection refused")))
      val resp = postEnvelope(validEnvelope, registry = stub)
      resp.status must beEqualTo(Status.ServiceUnavailable)
      (bodyJson(resp) \\ "accepted").headOption must beSome(Json.False)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("schema_registry_unavailable")
    }

    "return 503 when the events sink is unreachable" in {
      val resp = postEnvelope(validEnvelope, sink = failingSink(Unavailable("connection refused")))
      resp.status must beEqualTo(Status.ServiceUnavailable)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("sink_unavailable")
    }

    "return 504 when the events sink times out" in {
      postEnvelope(validEnvelope, sink = failingSink(Timeout)).status must beEqualTo(Status.GatewayTimeout)
    }

    "return 502 when the events sink write fails for an unmapped reason" in {
      val resp = postEnvelope(validEnvelope, sink = failingSink(WriteFailed("disk full")))
      resp.status must beEqualTo(Status.BadGateway)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must contain("sink_write_failed")
    }

    "return 503 accepted=false when an owned failure cannot be DLQ'd because the DLQ is down" in {
      val stub = new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject)))
      val resp = postEnvelope(validEnvelope, registry = stub, dlqSink = failingDlq(Unavailable("dlq down")))
      resp.status must beEqualTo(Status.ServiceUnavailable)
      (bodyJson(resp) \\ "accepted").headOption must beSome(Json.False)
      (bodyJson(resp) \\ "type").flatMap(_.asString) must beEqualTo(List("sink_unavailable"))
    }
  }
}
