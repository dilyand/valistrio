package valistrio.core.post

import io.circe.parser
import org.specs2.mutable.Specification
import valistrio.core.domain.{Event, ValidatedEvent}

/** Unit tests for the pure part of the write path (what lands on the topic). The live
  * producer needs a broker, so it is covered by the IT suite.
  */
class KafkaSinkSpec extends Specification {

  private val eventJson =
    """{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":{"meta":{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"},"body":{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}}}"""

  private val json      = parser.parse(eventJson).toOption.get
  private val validated = ValidatedEvent.of(Event.fromJson(json).toOption.get).toOption.get

  "The written record" should {
    "carry the original JSON verbatim as the value" in {
      validated.json.noSpaces must beEqualTo(json.noSpaces)
    }

    "use the event_id as the key, so all writes for one event land on one partition" in {
      validated.eventId must beEqualTo("018f1e2a-dead-beef-cafe-000000000000")
    }
  }
}
