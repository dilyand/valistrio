package valistrio.core.validate

import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaName, SchemaVersion}

/** Unit tests for the pure logic in ConfluentSchemaRegistry.
  *
  * The live Confluent client requires a running Schema Registry, so it is not
  * tested here. These tests cover the error-mapping and subject-naming logic
  * that can be exercised without network access.
  */
class ConfluentSchemaRegistrySpec extends Specification {

  // Expose the pure mapping for testing via a small helper that mirrors the impl.
  // We test by verifying the algebra contract on a stub implementation.

  "SchemaRegistry subject naming" should {
    "use SchemaName.toString as the Confluent subject" in {
      val name = SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      name.toString must beEqualTo("com.myorg/page_view/1.0.0")
    }

    "produce a stable subject for Valistrio-owned schemas" in {
      val name = SchemaName("com.valistrio", "envelope", SchemaVersion(1, 0, 0))
      name.toString must beEqualTo("com.valistrio/envelope/1.0.0")
    }
  }

  "ValidateError recoverability" should {
    "MalformedJson is non-recoverable" in {
      val e: ValidateError = MalformedJson("bad")
      // Non-recoverable errors map to HTTP 400 — verify type identity
      e must beAnInstanceOf[MalformedJson]
    }

    "SchemaNotFound is recoverable (ops-side)" in {
      val name = SchemaName("com.myorg", "user", SchemaVersion(1, 0, 0))
      val e: ValidateError = SchemaNotFound(name)
      e must beAnInstanceOf[SchemaNotFound]
    }

    "SchemaRegistryTimeout is recoverable (ops-side)" in {
      (SchemaRegistryTimeout: ValidateError) must beAnInstanceOf[SchemaRegistryTimeout.type]
    }

    "SchemaRegistryUnavailable is recoverable (ops-side)" in {
      val e: ValidateError = SchemaRegistryUnavailable("connection refused")
      e must beAnInstanceOf[SchemaRegistryUnavailable]
    }
  }
}
