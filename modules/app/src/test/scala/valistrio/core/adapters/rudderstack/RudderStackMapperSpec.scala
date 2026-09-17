package valistrio.core.adapters.rudderstack

import cats.syntax.either._
import io.circe.{Json, parser}
import org.specs2.mutable.Specification

class RudderStackMapperSpec extends Specification {

  private val FallbackId = "00000000-0000-4000-8000-fallbackfall"
  private val FallbackTs = "2000-01-01T00:00:00Z"

  private def mapWire(wire: String, eid: String = FallbackId, ts: String = FallbackTs): Either[String, Json] =
    parser.parse(wire).flatMap(_.as[RudderStackEvent]).leftMap(_.getMessage).flatMap(RudderStackMapper.map(_, eid, ts))

  private def field(doc: Json, path: String*): Option[Json] =
    path.foldLeft(doc.hcursor: io.circe.ACursor)(_.downField(_)).focus

  private def str(doc: Json, path: String*): Option[String] =
    field(doc, path: _*).flatMap(_.asString)

  // A full track event as the browser SDK enriches and POSTs it to /v1/track: the producer's data,
  // schema and contexts inside `properties`, wrapped in the SDK's own transport/enrichment fields
  // (type, event, anonymousId, channel, integrations, sentAt, context). The mapper reads only
  // `properties`, `messageId` and `originalTimestamp`, so the transport fields must not leak.
  private val trackWire =
    """{
      |  "type": "track",
      |  "event": "survey_create",
      |  "userId": null,
      |  "anonymousId": "a1b2c3d4-anon",
      |  "channel": "web",
      |  "integrations": {"All": true},
      |  "context": {
      |    "library": {"name": "RudderLabs JavaScript SDK", "version": "3.33.0"},
      |    "locale": "en-GB",
      |    "userAgent": "Mozilla/5.0",
      |    "page": {"path": "/create", "url": "https://demo.example/create"}
      |  },
      |  "messageId": "1b2c3d4e-0000-4000-8000-000000000001",
      |  "originalTimestamp": "2026-06-08T12:00:00.000Z",
      |  "sentAt": "2026-06-08T12:00:00.100Z",
      |  "properties": {
      |    "survey_id": "s-1",
      |    "title": "My survey",
      |    "question_count": 3,
      |    "schema": "com.askattest.demo/survey_create/1.0.0",
      |    "contexts": [
      |      {"schema": "com.askattest.demo/user/1.0.0", "data": {"id": "u-1"}},
      |      {"schema": "com.askattest.demo/session/1.0.0", "data": {"id": "sess-1"}}
      |    ]
      |  }
      |}""".stripMargin

  // A full page event as the browser SDK POSTs it to /v1/page.
  private val pageWire =
    """{
      |  "type": "page",
      |  "name": "results",
      |  "anonymousId": "a1b2c3d4-anon",
      |  "channel": "web",
      |  "context": {"library": {"name": "RudderLabs JavaScript SDK", "version": "3.33.0"}},
      |  "properties": {
      |    "name": "results",
      |    "path": "/results",
      |    "schema": "com.askattest.demo/page_view/1.0.0",
      |    "contexts": [{"schema": "com.askattest.demo/user/1.0.0", "data": {"id": "u-1"}}]
      |  },
      |  "messageId": "1b2c3d4e-0000-4000-8000-000000000002",
      |  "originalTimestamp": "2026-06-08T12:01:00.000Z",
      |  "sentAt": "2026-06-08T12:01:00.100Z"
      |}""".stripMargin

  "RudderStackMapper" should {

    "map a track event to the valistrio event document" in {
      val doc = mapWire(trackWire).toOption.get
      str(doc, "schema") must beSome("io.github.dilyand.valistrio/event/1.0.0")
      str(doc, "data", "meta", "event_id") must beSome("1b2c3d4e-0000-4000-8000-000000000001")
      str(doc, "data", "meta", "produced_at") must beSome("2026-06-08T12:00:00.000Z")
      str(doc, "data", "body", "schema") must beSome("com.askattest.demo/survey_create/1.0.0")
    }

    "carry every producer field into the body, dropping only the two ref-carrying keys" in {
      val doc  = mapWire(trackWire).toOption.get
      val keys = field(doc, "data", "body", "data").flatMap(_.asObject).map(_.keys.toList).getOrElse(Nil)
      keys must containTheSameElementsAs(List("survey_id", "title", "question_count"))
      keys must not(contain("schema"))
      keys must not(contain("contexts"))
    }

    "never leak the SDK's transport fields into the event" in {
      // type/event/anonymousId/channel/integrations/context/sentAt sit outside properties, so none
      // can reach body.data — a faithful map keeps the producer's payload and drops SDK plumbing.
      val doc       = mapWire(trackWire).toOption.get
      val keys      = field(doc, "data", "body", "data").flatMap(_.asObject).map(_.keys.toList).getOrElse(Nil)
      val transport = List("type", "event", "anonymousId", "channel", "integrations", "context", "sentAt")
      keys.intersect(transport) must beEmpty
    }

    "carry the contexts across as an array" in {
      val doc = mapWire(trackWire).toOption.get
      field(doc, "data", "contexts").flatMap(_.asArray).map(_.size) must beSome(2)
    }

    "map a page event the same way, keeping name and path in the body" in {
      val doc = mapWire(pageWire).toOption.get
      str(doc, "data", "body", "schema") must beSome("com.askattest.demo/page_view/1.0.0")
      val keys = field(doc, "data", "body", "data").flatMap(_.asObject).map(_.keys.toList).getOrElse(Nil)
      keys must containTheSameElementsAs(List("name", "path"))
    }

    "omit contexts when the producer sends none" in {
      val wire = """{"type":"track","properties":{"x":1,"schema":"com.myorg/thing/1.0.0"},"messageId":"m-1","originalTimestamp":"t"}"""
      field(mapWire(wire).toOption.get, "data", "contexts") must beNone
    }

    "fall back to the supplied event_id and produced_at when messageId/originalTimestamp are absent" in {
      val wire = """{"type":"track","properties":{"x":1,"schema":"com.myorg/thing/1.0.0"}}"""
      val doc  = mapWire(wire).toOption.get
      str(doc, "data", "meta", "event_id") must beSome(FallbackId)
      str(doc, "data", "meta", "produced_at") must beSome(FallbackTs)
    }

    "reject when properties is absent" in {
      mapWire("""{"type":"track","messageId":"m-1"}""") must beLeft
    }

    "reject when properties.schema is absent" in {
      mapWire("""{"type":"track","properties":{"x":1}}""") must beLeft
    }

    "reject when properties.schema is not a valid schema ref" in {
      mapWire("""{"type":"track","properties":{"schema":"not a ref"}}""") must beLeft
    }

    "reject when properties.contexts is not an array" in {
      mapWire("""{"type":"track","properties":{"schema":"com.myorg/thing/1.0.0","contexts":{}}}""") must beLeft
    }

    "reject when a context is missing its data" in {
      mapWire("""{"type":"track","properties":{"schema":"com.myorg/thing/1.0.0","contexts":[{"schema":"com.myorg/user/1.0.0"}]}}""") must beLeft
    }
  }
}
