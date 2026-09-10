package valistrio.core.domain

import io.circe.parser
import org.specs2.mutable.Specification

/** Extraction of the navigable [[Event]] from JSON. Structural rejection (unknown fields,
  * missing fields, empty contexts, bad refs) is the event schema's job now, so it is covered
  * by the integration suite; here we only check that a well-formed event navigates correctly.
  */
class EventSpec extends Specification {

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validBody =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""
  private val validContext =
    """{"schema":"com.myorg/user/1.0.0","data":{"user_id":"u-123"}}"""

  private def eventJson(data: String) =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":$data}"""
  private def eventData(contexts: Option[String] = None) = {
    val ctx = contexts.map(c => s""","contexts":$c""").getOrElse("")
    s"""{"meta":$validMeta,"body":$validBody$ctx}"""
  }

  private def extract(json: String) =
    Event.fromJson(parser.parse(json).toOption.get)

  "Event.fromJson" should {
    "extract a valid event without contexts" in {
      extract(eventJson(eventData())) must beRight.like { case e =>
        e.schema must beEqualTo(SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0)))
        e.data.body.schema must beEqualTo(SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0)))
        e.data.contexts must beNone
      }
    }

    "extract a single context" in {
      extract(eventJson(eventData(contexts = Some(s"[$validContext]")))) must beRight.like { case e =>
        e.data.contexts must beSome.like { case nel =>
          nel.head.schema must beEqualTo(SchemaRef("com.myorg", "user", SchemaVersion(1, 0, 0)))
        }
      }
    }

    "extract multiple contexts" in {
      extract(eventJson(eventData(contexts = Some(s"[$validContext,$validContext]")))) must beRight.like { case e =>
        e.data.contexts must beSome.like { case nel => nel.size must beEqualTo(2) }
      }
    }

    "carry the original JSON verbatim" in {
      val json = parser.parse(eventJson(eventData())).toOption.get
      Event.fromJson(json) must beRight.like { case e => e.json must beEqualTo(json) }
    }
  }
}
