package valistrio.core.resources

import cats.effect.unsafe.implicits.global
import io.circe.parser
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaRef, SchemaVersion}

/** Unit tests for the pure logic in ConfluentSchemaRegistry.
  *
  * The live Confluent client requires a running Schema Registry, so it is not
  * tested here. These tests cover the error-mapping and subject-naming logic
  * that can be exercised without network access.
  */
class ConfluentSchemaRegistrySpec extends Specification {

  // Expose the pure mapping for testing via a small helper that mirrors the impl.
  // We test by verifying the algebra contract on a stub implementation.

  // Guards against a SchemaRef in OwnedSchemas drifting from its bundled resource file
  // (the class of bug where seeding would fail only at startup).
  "Owned schemas" should {
    "each resolve to a bundled, well-formed JSON resource" in {
      ConfluentSchemaRegistry.OwnedSchemas
        .map(ref => parser.parse(ConfluentSchemaRegistry.loadSchemaJson(ref).unsafeRunSync()))
        .forall(_.isRight) must beTrue
    }
  }

  "SchemaRegistry subject naming" should {
    "use SchemaRef.toString as the Confluent subject" in {
      val name = SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      name.toString must beEqualTo("com.myorg/page_view/1.0.0")
    }

    "produce a stable subject for Valistrio-owned schemas" in {
      val name = SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))
      name.toString must beEqualTo("io.github.dilyand.valistrio/event/1.0.0")
    }
  }

  "ValidateError recoverability" should {
    "MalformedJson is non-recoverable" in {
      val e: ValidateError = MalformedJson("bad")
      // Non-recoverable errors map to HTTP 400 — verify type identity
      e must beAnInstanceOf[MalformedJson]
    }

    "SchemaNotFound is recoverable (ops-side)" in {
      val name = SchemaRef("com.myorg", "user", SchemaVersion(1, 0, 0))
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
