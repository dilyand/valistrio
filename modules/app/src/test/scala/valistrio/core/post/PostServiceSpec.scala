package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.{IO, Ref}
import cats.effect.unsafe.implicits.global
import io.circe.Json
import io.circe.parser
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.{SchemaRef, ValidatedEvent}
import valistrio.core.validate.{SchemaRegistry, ValidateService}

class PostServiceSpec extends Specification {

  // ---- Stubs ----

  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry {
    def validate(ref: SchemaRef, data: Json): IO[Unit] =
      IO.fromEither(responses.getOrElse(ref.toString, default))
    def register(ref: SchemaRef, schemaJson: String): IO[Unit] = IO.unit
  }

  /** Records every write; raises `result` when it is a Left (a raised SinkError). */
  private class RecordingSink[A](ref: Ref[IO, Vector[A]], result: Either[SinkError, Unit]) {
    def record(a: A): IO[Unit]  = ref.update(_ :+ a) >> IO.fromEither(result)
    def written: IO[Vector[A]]  = ref.get
  }

  private class StubSink(rec: RecordingSink[ValidatedEvent]) extends Sink {
    def write(event: ValidatedEvent): IO[Unit] = rec.record(event)
  }
  private class StubDlqSink(rec: RecordingSink[FailedEvent]) extends DlqSink {
    def write(failed: FailedEvent): IO[Unit] = rec.record(failed)
  }

  private def sink(result: Either[SinkError, Unit] = Right(())): (StubSink, RecordingSink[ValidatedEvent]) = {
    val rec = new RecordingSink(Ref.unsafe[IO, Vector[ValidatedEvent]](Vector.empty), result)
    (new StubSink(rec), rec)
  }
  private def dlq(result: Either[SinkError, Unit] = Right(())): (StubDlqSink, RecordingSink[FailedEvent]) = {
    val rec = new RecordingSink(Ref.unsafe[IO, Vector[FailedEvent]](Vector.empty), result)
    (new StubDlqSink(rec), rec)
  }

  private def stubOk = new StubSchemaRegistry()
  private def stubFor(subject: String, result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(responses = Map(subject -> result))

  // ---- Fixtures ----

  private val MaxBytes = 2097152L

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validBody =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""

  private val validEnvelope =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":$validMeta,"body":$validBody}}"""

  private val bodySubject = "com.myorg/page_view/1.0.0"

  // ---- Helpers ----

  private def run(registry: SchemaRegistry, s: Sink, d: DlqSink, body: String): PostResponse =
    new PostService(new ValidateService(registry), s, d, MaxBytes).post(body).unsafeRunSync()

  private def errorTypes(resp: PostResponse): List[String] = resp match {
    case PostResponse.Written        => Nil
    case PostResponse.Dlqd(errors)   => errors.toList.map(_.`type`)
    case PostResponse.Failed(errors) => errors.toList.map(_.`type`)
  }

  // ---- Tests ----

  "PostService" should {

    "return Written and write to the events sink when validation succeeds" in {
      val (s, sRec) = sink()
      val (d, dRec) = dlq()
      run(stubOk, s, d, validEnvelope) must beEqualTo(PostResponse.Written)
      sRec.written.unsafeRunSync() must haveSize(1)
      dRec.written.unsafeRunSync() must beEmpty
    }

    "return Dlqd and route to the DLQ (not the events sink) when the body is malformed JSON" in {
      val (s, sRec) = sink()
      val (d, dRec) = dlq()
      val resp = run(stubOk, s, d, "not json at all")
      errorTypes(resp) must beEqualTo(List("malformed_json"))
      resp must beAnInstanceOf[PostResponse.Dlqd]
      sRec.written.unsafeRunSync() must beEmpty
      dRec.written.unsafeRunSync() must haveSize(1)
    }

    "salvage the original as a JSON string on the DLQ when the body is malformed" in {
      val (s, _)    = sink()
      val (d, dRec) = dlq()
      run(stubOk, s, d, "not json at all")
      dRec.written.unsafeRunSync().head.original must beSome(Json.fromString("not json at all"))
    }

    "return Dlqd and salvage the original JSON when the body schema is not registered" in {
      val (s, sRec)  = sink()
      val (d, dRec)  = dlq()
      val registry   = stubFor(bodySubject, Left(SchemaNotFound(SchemaRef.parse(bodySubject).toOption.get)))
      val resp       = run(registry, s, d, validEnvelope)
      errorTypes(resp) must contain("schema_not_found")
      resp must beAnInstanceOf[PostResponse.Dlqd]
      sRec.written.unsafeRunSync() must beEmpty
      dRec.written.unsafeRunSync().head.original must beSome(parser.parse(validEnvelope).toOption.get)
    }

    "return Dlqd when the payload fails schema validation" in {
      val (s, _)    = sink()
      val (d, dRec) = dlq()
      val err       = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val resp      = run(stubFor(bodySubject, Left(err)), s, d, validEnvelope)
      errorTypes(resp) must beEqualTo(List("schema_validation_failed"))
      resp must beAnInstanceOf[PostResponse.Dlqd]
      dRec.written.unsafeRunSync() must haveSize(1)
    }

    "return Failed (not DLQ) when the registry is unavailable — a Retry disposition" in {
      val (s, _)    = sink()
      val (d, dRec) = dlq()
      val registry  = new StubSchemaRegistry(default = Left(SchemaRegistryUnavailable("connection refused")))
      val resp      = run(registry, s, d, validEnvelope)
      errorTypes(resp) must contain("schema_registry_unavailable")
      resp must beAnInstanceOf[PostResponse.Failed]
      dRec.written.unsafeRunSync() must beEmpty
    }

    "return Failed when the events write fails — a valid event is not DLQ'd" in {
      val (s, _)    = sink(Left(WriteFailed("disk full")))
      val (d, dRec) = dlq()
      val resp      = run(stubOk, s, d, validEnvelope)
      errorTypes(resp) must beEqualTo(List("sink_write_failed"))
      resp must beAnInstanceOf[PostResponse.Failed]
      dRec.written.unsafeRunSync() must beEmpty
    }

    "return Failed with only the sink error when the DLQ write also fails" in {
      val (s, _) = sink()
      val (d, _) = dlq(Left(Unavailable("dlq broker down")))
      val err    = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val resp   = run(stubFor(bodySubject, Left(err)), s, d, validEnvelope)
      errorTypes(resp) must beEqualTo(List("sink_unavailable"))
      resp must beAnInstanceOf[PostResponse.Failed]
    }
  }
}
