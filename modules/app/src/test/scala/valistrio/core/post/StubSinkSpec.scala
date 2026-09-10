package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.unsafe.implicits.global
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.SinkError.WriteFailed
import valistrio.core.domain._

class StubSinkSpec extends Specification {

  private val envelope = Event(
    SchemaRef("com.valistrio", "envelope", SchemaVersion(1, 0, 0)),
    EventData(
      EventMeta("018f1e2a-dead-beef-cafe-000000000000", "2026-06-08T12:00:00Z"),
      TypedData(
        SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0)),
        io.circe.Json.obj("page_url" -> io.circe.Json.fromString("https://example.com"))
      ),
      None: Option[NonEmptyList[TypedData]]
    )
  )

  "StubSink.succeeding" should {
    "record writes and report success" in {
      val (result, written) = (for {
        sink    <- StubSink.succeeding
        result  <- sink.write(envelope)
        written <- sink.written
      } yield (result, written)).unsafeRunSync()

      result must beRight(())
      written must beEqualTo(Vector(envelope))
    }
  }

  "StubSink.failingWith" should {
    "record the write but report the configured error" in {
      val (result, written) = (for {
        sink    <- StubSink.failingWith(WriteFailed("boom"))
        result  <- sink.write(envelope)
        written <- sink.written
      } yield (result, written)).unsafeRunSync()

      result must beLeft(WriteFailed("boom"))
      written must beEqualTo(Vector(envelope))
    }
  }
}
