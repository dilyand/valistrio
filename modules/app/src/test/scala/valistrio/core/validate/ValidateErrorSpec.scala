package valistrio.core.validate

import cats.data.NonEmptyList
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.{ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaName, SchemaVersion}

class ValidateErrorSpec extends Specification {
  "ValidateError" should {
    "MalformedJson msg includes the cause" in {
      MalformedJson("unexpected token at position 5").msg must contain("unexpected token at position 5")
    }

    "StructuralDecodeError msg includes the cause" in {
      StructuralDecodeError("Unknown field: foo").msg must contain("Unknown field: foo")
    }

    "SchemaNotFound msg includes the schema name" in {
      val name = SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      SchemaNotFound(name).msg must contain("com.myorg/page_view/1.0.0")
    }

    "SchemaRegistryUnavailable msg includes the cause" in {
      SchemaRegistryUnavailable("connection refused").msg must contain("connection refused")
    }

    "SchemaRegistryTimeout has a stable msg" in {
      SchemaRegistryTimeout.msg must contain("timed out")
    }

    "ValidationFailed msg reports the error count" in {
      val errors = NonEmptyList.of(
        ValidationError("$.foo", "must be a string"),
        ValidationError("$.bar", "required property missing")
      )
      ValidationFailed(errors).msg must contain("2 error(s)")
    }

    "ValidationFailed carries all errors" in {
      val errors = NonEmptyList.of(
        ValidationError("$.a", "err1"),
        ValidationError("$.b", "err2"),
        ValidationError("$.c", "err3")
      )
      ValidationFailed(errors).errors.size must beEqualTo(3)
    }

    "MalformedJson is a ValidateError" in {
      (MalformedJson("x"): ValidateError) must beAnInstanceOf[ValidateError]
    }

    "ValidationFailed is a ValidateError" in {
      val e = ValidationFailed(NonEmptyList.one(ValidationError("$", "x")))
      (e: ValidateError) must beAnInstanceOf[ValidateError]
    }
  }
}
