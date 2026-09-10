package valistrio.core.validate

import cats.data.NonEmptyList
import io.circe.syntax._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.{ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaRef, SchemaVersion}

class ValidateResponseSpec extends Specification {

  "ValidateResponse encoder" should {
    "encode Success as { valid: true }" in {
      val json = (ValidateResponse.Success: ValidateResponse).asJson
      json.hcursor.get[Boolean]("valid") must beRight(true)
      json.hcursor.downField("errors").succeeded must beFalse
    }

    "encode Failure with valid: false and errors array" in {
      val error = ValidateResponseError("malformed_json", recoverable = false, path = None, "bad json")
      val json  = (ValidateResponse.Failure(NonEmptyList.one(error)): ValidateResponse).asJson
      json.hcursor.get[Boolean]("valid") must beRight(false)
      json.hcursor.downField("errors").focus must beSome
    }
  }

  "ValidateResponseError encoder" should {
    "include path when present" in {
      val e    = ValidateResponseError("schema_validation_failed", recoverable = true, path = Some("$.foo"), "must be string")
      val json = e.asJson
      json.hcursor.get[String]("path") must beRight("$.foo")
    }

    "omit path when absent" in {
      val e    = ValidateResponseError("malformed_json", recoverable = false, path = None, "bad json")
      val json = e.asJson
      json.hcursor.downField("path").succeeded must beFalse
    }
  }

  "ValidateResponseError.from" should {
    "map MalformedJson to a non-recoverable entry" in {
      val errors = ValidateResponseError.from(MalformedJson("bad"))
      errors.size must beEqualTo(1)
      errors.head.`type` must beEqualTo("malformed_json")
      errors.head.recoverable must beFalse
    }


    "map SchemaNotFound to a recoverable entry" in {
      val name   = SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      val errors = ValidateResponseError.from(SchemaNotFound(name))
      errors.head.`type` must beEqualTo("schema_not_found")
      errors.head.recoverable must beTrue
    }

    "map SchemaRegistryUnavailable to a recoverable entry" in {
      val errors = ValidateResponseError.from(SchemaRegistryUnavailable("timeout"))
      errors.head.`type` must beEqualTo("schema_registry_unavailable")
      errors.head.recoverable must beTrue
    }

    "map SchemaRegistryTimeout to a recoverable entry" in {
      val errors = ValidateResponseError.from(SchemaRegistryTimeout)
      errors.head.`type` must beEqualTo("schema_registry_timeout")
      errors.head.recoverable must beTrue
    }

    "map ValidationFailed to one entry per validation error" in {
      val validationErrors = NonEmptyList.of(
        ValidationError("$.foo", "must be a string"),
        ValidationError("$.bar", "required property missing")
      )
      val errors = ValidateResponseError.from(ValidationFailed(validationErrors))
      errors.size must beEqualTo(2)
      errors.head.`type` must beEqualTo("schema_validation_failed")
      errors.head.recoverable must beTrue
      errors.head.path must beSome("$.foo")
      errors.last.path must beSome("$.bar")
    }
  }
}
