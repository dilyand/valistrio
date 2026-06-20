package valistrio.core.domain

import io.circe.parser
import org.specs2.mutable.Specification

class TypedPayloadSpec extends Specification {

  private def decode(json: String) =
    parser.decode[TypedPayload](json)

  "TypedPayload decoder" should {
    "decode a valid payload" in {
      val result = decode("""{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}""")
      result must beRight(
        TypedPayload(
          SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0)),
          parser.parse("""{"page_url":"https://example.com"}""").toOption.get
        )
      )
    }

    "decode a payload with an empty data object" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":{}}""") must beRight
    }

    "reject an unknown extra key" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":{},"extra":"value"}""") must beLeft
    }

    "reject a missing schema field" in {
      decode("""{"data":{"a":1}}""") must beLeft
    }

    "reject a missing data field" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0"}""") must beLeft
    }

    "reject data as an array" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":[1,2,3]}""") must beLeft
    }

    "reject data as a string" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":"hello"}""") must beLeft
    }

    "reject data as a number" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":42}""") must beLeft
    }

    "reject data as null" in {
      decode("""{"schema":"com.myorg/page_view/1.0.0","data":null}""") must beLeft
    }

    "reject a bad schema format" in {
      decode("""{"schema":"not-a-valid-schema","data":{}}""") must beLeft
    }

    "reject a non-object input" in {
      decode("""["schema","data"]""") must beLeft
    }
  }
}
