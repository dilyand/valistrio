package valistrio.core.domain

import io.circe.parser
import org.specs2.mutable.Specification

class EventMetaSpec extends Specification {

  private def decode(json: String) =
    parser.decode[EventMeta](json)

  "EventMeta decoder" should {
    "decode a valid meta object" in {
      decode("""{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}""") must
        beRight(EventMeta("018f1e2a-dead-beef-cafe-000000000000", "2026-06-08T12:00:00Z"))
    }

    "pass through unknown extra fields (lenient decoder)" in {
      decode("""{"event_id":"abc","produced_at":"2026-01-01T00:00:00Z","future_field":"ignored"}""") must
        beRight(EventMeta("abc", "2026-01-01T00:00:00Z"))
    }

    "reject a missing event_id" in {
      decode("""{"produced_at":"2026-06-08T12:00:00Z"}""") must beLeft
    }

    "reject a missing produced_at" in {
      decode("""{"event_id":"018f1e2a-dead-beef-cafe-000000000000"}""") must beLeft
    }

    "reject a non-string event_id" in {
      decode("""{"event_id":123,"produced_at":"2026-06-08T12:00:00Z"}""") must beLeft
    }

    "reject a non-string produced_at" in {
      decode("""{"event_id":"abc","produced_at":true}""") must beLeft
    }
  }
}
