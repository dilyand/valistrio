package valistrio.core.adapters.rudderstack

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.http4s._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.SchemaRef
import valistrio.core.domain.Writable.{FailedEvent, ValidatedEvent}
import valistrio.core.pipeline.{Ingestion, Validation}
import valistrio.core.resources.schemas.SchemaRegistry
import valistrio.core.resources.sinks.Sink

class RudderStackRoutesSpec extends Specification {

  // ---- Stubs ----

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

  private final class RecordingSink extends Sink[ValidatedEvent] {
    private val store                          = scala.collection.mutable.ArrayBuffer.empty[ValidatedEvent]
    def write(event: ValidatedEvent): IO[Unit] = IO { store += event; () }
    def count: Int                             = store.size
  }

  private def okSink: Sink[ValidatedEvent]                    = _ => IO.unit
  private def failingSink(e: SinkError): Sink[ValidatedEvent] = _ => IO.raiseError(e)
  private def okDlq: Sink[FailedEvent]                        = _ => IO.unit

  private val MaxBytes = 2097152L

  private def app(
    registry: SchemaRegistry = new StubSchemaRegistry(),
    sink: Sink[ValidatedEvent] = okSink,
    dlqSink: Sink[FailedEvent] = okDlq
  ): HttpApp[IO] =
    RudderStackRoutes(new Ingestion(new Validation(registry), sink, dlqSink, MaxBytes)).orNotFound

  private def send(path: String, body: String, httpApp: HttpApp[IO]): Response[IO] = {
    val req = Request[IO](method = Method.POST, uri = Uri.unsafeFromString(path)).withEntity(body)
    httpApp.run(req).unsafeRunSync()
  }

  // ---- Fixtures ----

  private val trackWire =
    """{
      |  "type": "track",
      |  "event": "survey_create",
      |  "properties": {
      |    "survey_id": "s-1",
      |    "schema": "com.askattest.demo/survey_create/1.0.0",
      |    "contexts": [{"schema": "com.askattest.demo/user/1.0.0", "data": {"id": "u-1"}}]
      |  },
      |  "messageId": "1b2c3d4e-0000-4000-8000-000000000001",
      |  "originalTimestamp": "2026-06-08T12:00:00.000Z"
      |}""".stripMargin

  private val pageWire =
    """{
      |  "type": "page",
      |  "properties": {"name": "results", "path": "/results", "schema": "com.askattest.demo/page_view/1.0.0"},
      |  "messageId": "1b2c3d4e-0000-4000-8000-000000000002",
      |  "originalTimestamp": "2026-06-08T12:01:00.000Z"
      |}""".stripMargin

  private val bodySubject = "com.askattest.demo/survey_create/1.0.0"

  // ---- Tests ----

  "POST /v1/track" should {

    "return 200 and write the mapped event when validation succeeds" in {
      val sink = new RecordingSink
      val resp = send("/v1/track", trackWire, app(sink = sink))
      resp.status must beEqualTo(Status.Ok)
      sink.count must beEqualTo(1)
    }

    "return a status-only response with no body" in {
      val resp = send("/v1/track", trackWire, app())
      resp.as[String].unsafeRunSync() must beEmpty
    }

    "return 400 when the body is not JSON" in {
      send("/v1/track", "not-json", app()).status must beEqualTo(Status.BadRequest)
    }

    "return 400 when properties.schema is missing" in {
      val wire = """{"type":"track","properties":{"x":1},"messageId":"m-1"}"""
      val sink = new RecordingSink
      val resp = send("/v1/track", wire, app(sink = sink))
      resp.status must beEqualTo(Status.BadRequest)
      sink.count must beEqualTo(0)
    }

    "return 200 without writing to the events sink when the body schema is not registered (owned, DLQ'd)" in {
      val sink = new RecordingSink
      val stub = new StubSchemaRegistry(responses = Map(bodySubject -> notFound(bodySubject)))
      val resp = send("/v1/track", trackWire, app(registry = stub, sink = sink))
      resp.status must beEqualTo(Status.Ok)
      sink.count must beEqualTo(0)
    }

    "pass a transient 503 through when the registry is unavailable" in {
      val stub = new StubSchemaRegistry(default = Left(SchemaRegistryUnavailable("connection refused")))
      send("/v1/track", trackWire, app(registry = stub)).status must beEqualTo(Status.ServiceUnavailable)
    }

    "pass a transient 502 through when the events sink write fails" in {
      send("/v1/track", trackWire, app(sink = failingSink(WriteFailed("disk full")))).status must beEqualTo(Status.BadGateway)
    }
  }

  "POST /v1/page" should {
    "return 200 and write the mapped event" in {
      val sink = new RecordingSink
      send("/v1/page", pageWire, app(sink = sink)).status must beEqualTo(Status.Ok)
      sink.count must beEqualTo(1)
    }
  }

  "An unsupported /v1 type" should {
    "not match the adapter (404)" in {
      send("/v1/identify", pageWire, app()).status must beEqualTo(Status.NotFound)
    }
  }
}
