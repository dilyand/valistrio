package valistrio.core.adapters

import io.circe.Json
import io.circe.syntax._
import org.specs2.mutable.Specification
import valistrio.core.domain.SchemaRef

class EventDocumentSpec extends Specification {

  private def ref(s: String): SchemaRef = SchemaRef.parse(s).toOption.get

  private val body =
    TypedPayload(ref("com.myorg/page_view/1.0.0"), Json.obj("page_url" -> "https://example.com".asJson))
  private val userCtx =
    TypedPayload(ref("com.myorg/user/1.0.0"), Json.obj("user_id" -> "u-1".asJson))

  private val eventId    = "018f1e2a-dead-beef-cafe-000000000000"
  private val producedAt = "2026-06-08T12:00:00Z"

  "EventDocument.build" should {

    "wrap the body under the event schema with meta" in {
      val doc = EventDocument.build(eventId, producedAt, body, Nil)
      doc.hcursor.get[String]("schema") must beRight("io.github.dilyand.valistrio/event/1.0.0")
      val meta = doc.hcursor.downField("data").downField("meta")
      meta.get[String]("event_id") must beRight(eventId)
      meta.get[String]("produced_at") must beRight(producedAt)
    }

    "render the body schema ref as a group/name/version string" in {
      val doc = EventDocument.build(eventId, producedAt, body, Nil)
      doc.hcursor.downField("data").downField("body").get[String]("schema") must beRight("com.myorg/page_view/1.0.0")
    }

    "omit contexts entirely when there are none" in {
      val doc = EventDocument.build(eventId, producedAt, body, Nil)
      doc.hcursor.downField("data").downField("contexts").focus must beNone
    }

    "include contexts as an array when present" in {
      val doc = EventDocument.build(eventId, producedAt, body, List(userCtx))
      doc.hcursor.downField("data").downField("contexts").as[List[Json]].map(_.size) must beRight(1)
    }
  }
}
