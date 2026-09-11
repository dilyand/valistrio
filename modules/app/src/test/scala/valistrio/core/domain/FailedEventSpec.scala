package valistrio.core.domain

import cats.data.NonEmptyList
import io.circe.Json
import io.circe.syntax._
import org.specs2.mutable.Specification

import java.time.Instant

class FailedEventSpec extends Specification {

  private val failedAt = Instant.parse("2026-06-08T12:00:00Z")
  private val errors   = NonEmptyList.one(ResponseError("schema_validation_failed", recoverable = true, Some("$.page_url"), "must be a string"))
  private val original = Json.obj("schema" -> Json.fromString("com.myorg/page_view/1.0.0"))

  "FailedEvent encoder" should {

    "carry the original, errors and failed_at, with no truncation flag when it fits" in {
      val json = FailedEvent.of(original, errors, failedAt, maxBytes = 2097152L).asJson
      (json \\ "original").headOption must beSome(original)
      (json \\ "failed_at").flatMap(_.asString) must beEqualTo(List("2026-06-08T12:00:00Z"))
      (json \\ "type").flatMap(_.asString) must contain("schema_validation_failed")
      (json \\ "original_truncated") must beEmpty
    }

    "drop the original and flag truncation when it exceeds maxBytes" in {
      val json = FailedEvent.of(original, errors, failedAt, maxBytes = 4L).asJson
      (json \\ "original").headOption must beSome(Json.Null)
      (json \\ "original_truncated").headOption must beSome(Json.True)
      (json \\ "type").flatMap(_.asString) must contain("schema_validation_failed")
    }

    "preserve a raw malformed body faithfully as a JSON string" in {
      val raw  = Json.fromString("not json at all")
      val json = FailedEvent.of(raw, errors, failedAt, maxBytes = 2097152L).asJson
      (json \\ "original").headOption must beSome(raw)
    }
  }
}
