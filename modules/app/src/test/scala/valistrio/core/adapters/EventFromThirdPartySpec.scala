package valistrio.core.adapters

import io.circe.Json
import io.circe.syntax._
import org.specs2.mutable.Specification
import valistrio.core.domain.SchemaRef

class EventFromThirdPartySpec extends Specification {

  private def ref(s: String): SchemaRef = SchemaRef.parse(s).toOption.get

  private val bodySchema = ref("com.myorg/page_view/1.0.0")
  private val bodyData   = Json.obj("page_url" -> "https://example.com".asJson)
  private val userCtx    = (ref("com.myorg/user/1.0.0"), Json.obj("user_id" -> "u-1".asJson))

  private val eventId    = "018f1e2a-dead-beef-cafe-000000000000"
  private val producedAt = "2026-06-08T12:00:00Z"

  "EventFromThirdParty.build" should {

    "wrap the body under the event schema with meta" in {
      val doc = EventFromThirdParty.build(eventId, producedAt, bodySchema, bodyData, Nil)
      doc.hcursor.get[String]("schema") must beRight("io.github.dilyand.valistrio/event/1.0.0")
      val meta = doc.hcursor.downField("data").downField("meta")
      meta.get[String]("event_id") must beRight(eventId)
      meta.get[String]("produced_at") must beRight(producedAt)
    }

    "render the body as a schema ref plus its data" in {
      val doc  = EventFromThirdParty.build(eventId, producedAt, bodySchema, bodyData, Nil)
      val body = doc.hcursor.downField("data").downField("body")
      body.get[String]("schema") must beRight("com.myorg/page_view/1.0.0")
      body.downField("data").get[String]("page_url") must beRight("https://example.com")
    }

    "omit contexts entirely when there are none" in {
      val doc = EventFromThirdParty.build(eventId, producedAt, bodySchema, bodyData, Nil)
      doc.hcursor.downField("data").downField("contexts").focus must beNone
    }

    "include each context as a schema/data pair when present" in {
      val doc = EventFromThirdParty.build(eventId, producedAt, bodySchema, bodyData, List(userCtx))
      val ctx = doc.hcursor.downField("data").downField("contexts").as[List[Json]]
      ctx.map(_.size) must beRight(1)
      ctx.toOption.flatMap(_.headOption).flatMap(c => c.hcursor.get[String]("schema").toOption) must beSome("com.myorg/user/1.0.0")
    }
  }
}
