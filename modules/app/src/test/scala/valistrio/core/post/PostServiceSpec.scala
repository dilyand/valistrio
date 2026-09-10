package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.SchemaRef
import valistrio.core.validate.{SchemaRegistry, ValidateService}

class PostServiceSpec extends Specification {

  // ---- Stub registry (mirrors ValidateServiceSpec's) ----

  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry {
    def validate(name: SchemaRef, data: Json): IO[Either[ValidateError, Unit]] =
      IO.pure(responses.getOrElse(name.toString, default))
    def register(name: SchemaRef, schemaJson: String): IO[Unit] = IO.unit
  }

  private def stubOk = new StubSchemaRegistry()
  private def stubFor(subject: String, result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(responses = Map(subject -> result))

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validBody =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""

  private val validEnvelope =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":$validBody}}"""

  private val bodySubject = "com.myorg/page_view/1.0.0"

  // ---- Helpers ----

  private def run(registry: SchemaRegistry, sink: Sink, body: String): PostResponse =
    new PostService(new ValidateService(registry), sink).post(body).unsafeRunSync()

  private def errorTypes(resp: PostResponse): List[String] = resp match {
    case PostResponse.Failure(errors) => errors.toList.map(_.`type`)
    case PostResponse.Written         => Nil
  }

  // ---- Tests ----

  "PostService" should {

    "return Written and write the event when validation succeeds" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      run(stubOk, sink, validEnvelope) must beEqualTo(PostResponse.Written)
      sink.written.unsafeRunSync() must haveSize(1)
    }

    "return Failure(malformed_json) and not write when the body is not valid JSON" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      errorTypes(run(stubOk, sink, "not json at all")) must beEqualTo(List("malformed_json"))
      sink.written.unsafeRunSync() must beEmpty
    }

    "return validation Failure and not write when the body schema is not registered" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      val registry = stubFor(bodySubject, Left(SchemaNotFound(SchemaRef.parse(bodySubject).toOption.get)))
      errorTypes(run(registry, sink, validEnvelope)) must contain("schema_not_found")
      sink.written.unsafeRunSync() must beEmpty
    }

    "return validation Failure and not write when the payload fails schema validation" in {
      val sink = StubSink.succeeding.unsafeRunSync()
      val err  = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      errorTypes(run(stubFor(bodySubject, Left(err)), sink, validEnvelope)) must beEqualTo(List("schema_validation_failed"))
      sink.written.unsafeRunSync() must beEmpty
    }

    "return Failure(sink_unavailable) when the sink reports it's unreachable" in {
      val sink = StubSink.failingWith(Unavailable("connection refused")).unsafeRunSync()
      errorTypes(run(stubOk, sink, validEnvelope)) must beEqualTo(List("sink_unavailable"))
      // The write was attempted (and recorded by the stub) even though it reported failure
      sink.written.unsafeRunSync() must haveSize(1)
    }

    "return Failure(sink_timeout) when the sink times out" in {
      val sink = StubSink.failingWith(Timeout).unsafeRunSync()
      errorTypes(run(stubOk, sink, validEnvelope)) must beEqualTo(List("sink_timeout"))
    }

    "return Failure(sink_write_failed) when the sink write fails" in {
      val sink = StubSink.failingWith(WriteFailed("disk full")).unsafeRunSync()
      val resp = run(stubOk, sink, validEnvelope)
      errorTypes(resp) must beEqualTo(List("sink_write_failed"))
      resp must beEqualTo(PostResponse.Failure(NonEmptyList.one(
        PostResponseError("sink_write_failed", recoverable = true, path = None, "disk full")
      )))
    }

    "mark sink errors as recoverable" in {
      val sink = StubSink.failingWith(WriteFailed("boom")).unsafeRunSync()
      run(stubOk, sink, validEnvelope) match {
        case PostResponse.Failure(errors) => errors.toList.map(_.recoverable) must beEqualTo(List(true))
        case PostResponse.Written         => ko("expected Failure")
      }
    }
  }
}
