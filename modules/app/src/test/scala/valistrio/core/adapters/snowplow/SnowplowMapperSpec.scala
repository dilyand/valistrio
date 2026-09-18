package valistrio.core.adapters.snowplow

import cats.syntax.either._
import io.circe.{Json, parser}
import org.specs2.mutable.Specification

class SnowplowMapperSpec extends Specification {

  private val FallbackId = "00000000-0000-4000-8000-fallbackfall"
  private val FallbackTs = "2000-01-01T00:00:00Z"

  private def mapWire(wire: String, eid: String = FallbackId, ts: String = FallbackTs): Either[String, Json] =
    parser.parse(wire).flatMap(_.as[SnowplowEvent]).leftMap(_.getMessage).flatMap(SnowplowMapper.map(_, eid, ts))

  private def field(doc: Json, path: String*): Option[Json] =
    path.foldLeft(doc.hcursor: io.circe.ACursor)(_.downField(_)).focus

  private def str(doc: Json, path: String*): Option[String] =
    field(doc, path: _*).flatMap(_.asString)

  private def bodyKeys(doc: Json): List[String] =
    field(doc, "data", "body", "data").flatMap(_.asObject).map(_.keys.toList).getOrElse(Nil)

  // A self-describing event as the tp2 collector receives it: eid/dtm atomic fields, the SDK's own
  // transport fields (tv/p/aid/tna/stm), the body inside ue_pr as an unstruct_event wrapper around
  // the inner {schema, data}, and contexts inside co.
  private val ueWire =
    """{
      |  "e": "ue",
      |  "eid": "1b2c3d4e-0000-4000-8000-000000000001",
      |  "dtm": "1750000000000",
      |  "tv": "js-4.10.2", "p": "web", "aid": "demo", "tna": "sp", "stm": "1750000000100",
      |  "ue_pr": "{\"schema\":\"iglu:com.snowplowanalytics.snowplow/unstruct_event/jsonschema/1-0-0\",\"data\":{\"schema\":\"iglu:com.askattest.demo/survey_create/jsonschema/1-0-0\",\"data\":{\"survey_id\":\"s-1\",\"title\":\"My survey\",\"question_count\":3}}}",
      |  "co": "{\"schema\":\"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1\",\"data\":[{\"schema\":\"iglu:com.askattest.demo/user/jsonschema/1-0-0\",\"data\":{\"id\":\"u-1\"}},{\"schema\":\"iglu:com.askattest.demo/session/jsonschema/1-0-0\",\"data\":{\"id\":\"sess-1\"}}]}"
      |}""".stripMargin

  // A page view: no ue_pr; atomic url/page/refr; one context in co.
  private val pvWire =
    """{
      |  "e": "pv",
      |  "eid": "1b2c3d4e-0000-4000-8000-000000000002",
      |  "dtm": "1750000060000",
      |  "url": "http://localhost:5173/create",
      |  "page": "Create Survey",
      |  "refr": "http://localhost:5173/",
      |  "co": "{\"schema\":\"iglu:com.snowplowanalytics.snowplow/contexts/jsonschema/1-0-1\",\"data\":[{\"schema\":\"iglu:com.askattest.demo/user/jsonschema/1-0-0\",\"data\":{\"id\":\"u-1\"}}]}"
      |}""".stripMargin

  // The same self-describing body carried base64url-encoded in ue_px instead of plain in ue_pr.
  private val uePxWire =
    """{
      |  "e": "ue",
      |  "eid": "1b2c3d4e-0000-4000-8000-000000000003",
      |  "ue_px": "eyJzY2hlbWEiOiJpZ2x1OmNvbS5zbm93cGxvd2FuYWx5dGljcy5zbm93cGxvdy91bnN0cnVjdF9ldmVudC9qc29uc2NoZW1hLzEtMC0wIiwiZGF0YSI6eyJzY2hlbWEiOiJpZ2x1OmNvbS5hc2thdHRlc3QuZGVtby9yZXN1bHRzX3ZpZXcvanNvbnNjaGVtYS8xLTAtMCIsImRhdGEiOnsic3VydmV5X2lkIjoicy05In19fQ"
      |}""".stripMargin

  "SnowplowMapper" should {

    "map a self-describing event to the valistrio event document" in {
      val doc = mapWire(ueWire).toOption.get
      str(doc, "schema") must beSome("io.github.dilyand.valistrio/event/1.0.0")
      str(doc, "data", "meta", "event_id") must beSome("1b2c3d4e-0000-4000-8000-000000000001")
      str(doc, "data", "body", "schema") must beSome("com.askattest.demo/survey_create/1.0.0")
    }

    "carry the inner event data across as the body data, without the SDK's transport fields" in {
      val doc = mapWire(ueWire).toOption.get
      bodyKeys(doc) must containTheSameElementsAs(List("survey_id", "title", "question_count"))
      bodyKeys(doc).intersect(List("e", "eid", "dtm", "tv", "p", "aid", "tna", "stm")) must beEmpty
    }

    "translate the dtm epoch-millis into an ISO-8601 produced_at" in {
      str(mapWire(ueWire).toOption.get, "data", "meta", "produced_at") must beSome("2025-06-15T15:06:40Z")
    }

    "carry the contexts across, translating each iglu ref" in {
      val ctx = field(mapWire(ueWire).toOption.get, "data", "contexts").flatMap(_.asArray).getOrElse(Vector.empty)
      ctx.size must beEqualTo(2)
      ctx.headOption.flatMap(c => c.hcursor.get[String]("schema").toOption) must beSome("com.askattest.demo/user/1.0.0")
    }

    "map a page view to the Snowplow-native page-view schema with the atomic fields" in {
      val doc = mapWire(pvWire).toOption.get
      str(doc, "data", "body", "schema") must beSome("com.snowplowanalytics.snowplow/page_view/1.0.0")
      bodyKeys(doc) must containTheSameElementsAs(List("url", "page", "refr"))
    }

    "decode a base64url-encoded self-describing body (ue_px)" in {
      val doc = mapWire(uePxWire).toOption.get
      str(doc, "data", "body", "schema") must beSome("com.askattest.demo/results_view/1.0.0")
      bodyKeys(doc) must containTheSameElementsAs(List("survey_id"))
    }

    "fall back to the supplied event_id and produced_at when eid/dtm are absent" in {
      val doc = mapWire("""{"e":"pv","url":"http://example.com/x"}""").toOption.get
      str(doc, "data", "meta", "event_id") must beSome(FallbackId)
      str(doc, "data", "meta", "produced_at") must beSome(FallbackTs)
    }

    "omit contexts when the event carries none" in {
      field(mapWire("""{"e":"pv","url":"http://example.com/x"}""").toOption.get, "data", "contexts") must beNone
    }

    "reject an unsupported event type" in {
      mapWire("""{"e":"se","eid":"x"}""") must beLeft
    }

    "reject a self-describing event carrying neither ue_pr nor ue_px" in {
      mapWire("""{"e":"ue","eid":"x"}""") must beLeft
    }

    "reject an invalid iglu ref in the body" in {
      mapWire("""{"e":"ue","ue_pr":"{\"data\":{\"schema\":\"not-iglu\",\"data\":{}}}"}""") must beLeft
    }

    "reject a dtm that is not epoch-millis" in {
      mapWire("""{"e":"pv","url":"http://example.com/x","dtm":"not-millis"}""") must beLeft
    }
  }
}
