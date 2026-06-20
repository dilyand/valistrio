package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.parser
import org.specs2.mutable.Specification

class TransportEnvelopeSpec extends Specification {

  private def decode(json: String) =
    parser.decode[TransportEnvelope](json)

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""

  private val validEvent =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""

  private val validContext =
    """{"schema":"com.myorg/user/1.0.0","data":{"user_id":"u-123"}}"""

  private def envelope(data: String) =
    s"""{"schema":"com.valistrio/envelope/1.0.0","data":$data}"""

  private def envelopeData(meta: String = validMeta, event: String = validEvent, contexts: Option[String] = None) = {
    val ctxPart = contexts.map(c => s""","contexts":$c""").getOrElse("")
    s"""{"meta":$meta,"event":$event$ctxPart}"""
  }

  "TransportEnvelope decoder" should {
    "decode a valid envelope without contexts" in {
      decode(envelope(envelopeData())) must beRight
    }

    "decode a valid envelope with one context" in {
      val result = decode(envelope(envelopeData(contexts = Some(s"[$validContext]"))))
      result must beRight.like { case te =>
        te.data.contexts must beSome(NonEmptyList.one(
          TypedPayload(SchemaName("com.myorg", "user", SchemaVersion(1, 0, 0)),
            parser.parse("""{"user_id":"u-123"}""").toOption.get)
        ))
      }
    }

    "decode a valid envelope with multiple contexts" in {
      decode(envelope(envelopeData(contexts = Some(s"[$validContext,$validContext]")))) must beRight.like {
        case te => te.data.contexts must beSome.like { case nel => nel.size must beEqualTo(2) }
      }
    }

    "decode when contexts field is absent" in {
      decode(envelope(envelopeData())) must beRight.like {
        case te => te.data.contexts must beNone
      }
    }

    "reject an empty contexts array" in {
      decode(envelope(envelopeData(contexts = Some("[]")))) must beLeft
    }

    "reject a missing meta" in {
      decode(envelope(s"""{"event":$validEvent}""")) must beLeft
    }

    "reject a missing event" in {
      decode(envelope(s"""{"meta":$validMeta}""")) must beLeft
    }

    "reject an unknown key at the envelope level" in {
      decode(s"""{"schema":"com.valistrio/envelope/1.0.0","data":${envelopeData()},"extra":"bad"}""") must beLeft
    }

    "reject an unknown key in envelope data" in {
      decode(envelope(s"""{"meta":$validMeta,"event":$validEvent,"unknown":"field"}""")) must beLeft
    }

    "reject an unknown key inside the event TypedPayload" in {
      val badEvent = """{"schema":"com.myorg/page_view/1.0.0","data":{},"extra":"bad"}"""
      decode(envelope(envelopeData(event = badEvent))) must beLeft
    }

    "reject a non-object at the envelope level" in {
      decode("""["not","an","object"]""") must beLeft
    }

    "reject a bad envelope schema name" in {
      decode(s"""{"schema":"bad-schema","data":${envelopeData()}}""") must beLeft
    }
  }
}
