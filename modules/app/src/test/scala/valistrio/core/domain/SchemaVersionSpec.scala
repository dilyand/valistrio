package valistrio.core.domain

import org.specs2.mutable.Specification

class SchemaVersionSpec extends Specification {
  "SchemaVersion.parse" should {
    "parse a valid version" in {
      SchemaVersion.parse("1.2.3").toEither must beRight(SchemaVersion(1, 2, 3))
    }

    "parse an all-zero version" in {
      SchemaVersion.parse("0.0.0").toEither must beRight(SchemaVersion(0, 0, 0))
    }

    "parse a version with large numbers" in {
      SchemaVersion.parse("10.20.300").toEither must beRight(SchemaVersion(10, 20, 300))
    }

    "reject a prerelease suffix" in {
      SchemaVersion.parse("1.0.0-rc.1").toEither must beLeft
    }

    "reject a prerelease suffix (alpha)" in {
      SchemaVersion.parse("1.0.0-alpha").toEither must beLeft
    }

    "reject build metadata" in {
      SchemaVersion.parse("1.0.0+build.7").toEither must beLeft
    }

    "reject too few segments" in {
      SchemaVersion.parse("1.0").toEither must beLeft
    }

    "reject too many segments" in {
      SchemaVersion.parse("1.0.0.0").toEither must beLeft
    }

    "reject a non-integer segment" in {
      SchemaVersion.parse("1.a.0").toEither must beLeft
    }

    "reject leading zeros on major" in {
      SchemaVersion.parse("01.0.0").toEither must beLeft
    }

    "reject leading zeros on minor" in {
      SchemaVersion.parse("1.00.0").toEither must beLeft
    }

    "reject leading zeros on patch" in {
      SchemaVersion.parse("1.0.00").toEither must beLeft
    }

    "reject an empty string" in {
      SchemaVersion.parse("").toEither must beLeft
    }

    "reject a negative segment" in {
      SchemaVersion.parse("1.-1.0").toEither must beLeft
    }
  }

  "SchemaVersion.toString" should {
    "render as major.minor.patch" in {
      SchemaVersion(1, 2, 3).toString must beEqualTo("1.2.3")
    }
  }
}
