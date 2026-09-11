package valistrio.core.validate

import cats.data.NonEmptyList
import io.circe.syntax._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaRef, SchemaVersion}
import valistrio.core.http.ResponseError

class ValidateResponseSpec extends Specification {

  "ValidateResponse encoder" should {
    "encode Success as { valid: true }" in {
      val json = (ValidateResponse.Success: ValidateResponse).asJson
      json.hcursor.get[Boolean]("valid") must beRight(true)
      json.hcursor.downField("errors").succeeded must beFalse
    }

    "encode Failure with valid: false and an errors array" in {
      val error = ResponseError("malformed_json", recoverable = false, path = None, "bad json")
      val json  = (ValidateResponse.Failure(NonEmptyList.one(error)): ValidateResponse).asJson
      json.hcursor.get[Boolean]("valid") must beRight(false)
      json.hcursor.downField("errors").focus must beSome
    }
  }

  "ResponseError encoder" should {
    "include path when present" in {
      ResponseError("schema_validation_failed", recoverable = true, path = Some("$.foo"), "must be string").asJson
        .hcursor.get[String]("path") must beRight("$.foo")
    }
    "omit path when absent" in {
      ResponseError("malformed_json", recoverable = false, path = None, "bad json").asJson
        .hcursor.downField("path").succeeded must beFalse
    }
  }

  "ResponseError.from" should {
    "map MalformedJson to a non-recoverable entry" in {
      val errors = ResponseError.from(MalformedJson("bad"))
      errors.head.`type` must beEqualTo("malformed_json")
      errors.head.recoverable must beFalse
    }

    "map SchemaNotFound to a recoverable entry" in {
      val errors = ResponseError.from(SchemaNotFound(SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0))))
      errors.head.`type` must beEqualTo("schema_not_found")
      errors.head.recoverable must beTrue
    }

    "map SchemaRegistryUnavailable to a recoverable entry" in {
      ResponseError.from(SchemaRegistryUnavailable("timeout")).head.`type` must beEqualTo("schema_registry_unavailable")
    }

    "map SchemaRegistryTimeout to a recoverable entry" in {
      ResponseError.from(SchemaRegistryTimeout).head.`type` must beEqualTo("schema_registry_timeout")
    }

    "map ValidationFailed to one entry per validation error" in {
      val errors = ResponseError.from(ValidationFailed(NonEmptyList.of(
        ValidationError("$.foo", "must be a string"),
        ValidationError("$.bar", "required property missing")
      )))
      errors.size must beEqualTo(2)
      errors.head.`type` must beEqualTo("schema_validation_failed")
      errors.head.path must beSome("$.foo")
      errors.last.path must beSome("$.bar")
    }
  }
}
