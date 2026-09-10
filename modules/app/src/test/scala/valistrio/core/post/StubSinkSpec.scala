package valistrio.core.post

import cats.effect.unsafe.implicits.global
import io.circe.parser
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.SinkError.WriteFailed
import valistrio.core.domain.{Event, ValidatedEvent}

class StubSinkSpec extends Specification {

  private val json = parser.parse(
    """{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"},"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}}}"""
  ).toOption.get
  private val event: ValidatedEvent = ValidatedEvent.of(Event.fromJson(json).toOption.get).toOption.get

  "StubSink.succeeding" should {
    "record writes and report success" in {
      val (result, written) = (for {
        sink    <- StubSink.succeeding
        result  <- sink.write(event)
        written <- sink.written
      } yield (result, written)).unsafeRunSync()

      result must beRight(())
      written must beEqualTo(Vector(event))
    }
  }

  "StubSink.failingWith" should {
    "record the write but report the configured error" in {
      val (result, written) = (for {
        sink    <- StubSink.failingWith(WriteFailed("boom"))
        result  <- sink.write(event)
        written <- sink.written
      } yield (result, written)).unsafeRunSync()

      result must beLeft(WriteFailed("boom"))
      written must beEqualTo(Vector(event))
    }
  }
}
