package valistrio.core.domain

import org.specs2.mutable.Specification

class SchemaNameSpec extends Specification {
  "SchemaName.parse" should {
    "parse a valid schema name" in {
      SchemaName.parse("com.myorg/page_view/1.0.0") must beRight(
        SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      )
    }

    "parse a Valistrio-owned schema name" in {
      SchemaName.parse("com.valistrio/envelope/1.0.0") must beRight(
        SchemaName("com.valistrio", "envelope", SchemaVersion(1, 0, 0))
      )
    }

    "parse a single-segment group" in {
      SchemaName.parse("myorg/user/2.1.0") must beRight(
        SchemaName("myorg", "user", SchemaVersion(2, 1, 0))
      )
    }

    "parse a simple (single-word) name" in {
      SchemaName.parse("com.example/user/1.0.0") must beRight(
        SchemaName("com.example", "user", SchemaVersion(1, 0, 0))
      )
    }

    "reject an empty string" in {
      SchemaName.parse("") must beLeft
    }

    "reject too few separators (no slash)" in {
      SchemaName.parse("com.myorg") must beLeft
    }

    "reject too few separators (one slash)" in {
      SchemaName.parse("com.myorg/page_view") must beLeft
    }

    "reject too many separators (three slashes)" in {
      SchemaName.parse("com.myorg/page_view/1.0.0/extra") must beLeft
    }

    "reject an uppercase group" in {
      SchemaName.parse("Com.myorg/page_view/1.0.0") must beLeft
    }

    "reject a group with empty segment" in {
      SchemaName.parse("com..myorg/page_view/1.0.0") must beLeft
    }

    "reject a group starting with a digit" in {
      SchemaName.parse("1com.myorg/page_view/1.0.0") must beLeft
    }

    "reject an uppercase name" in {
      SchemaName.parse("com.myorg/PageView/1.0.0") must beLeft
    }

    "reject a name with hyphens" in {
      SchemaName.parse("com.myorg/page-view/1.0.0") must beLeft
    }

    "reject a name starting with underscore" in {
      SchemaName.parse("com.myorg/_page_view/1.0.0") must beLeft
    }

    "reject an invalid version" in {
      SchemaName.parse("com.myorg/page_view/1.0") must beLeft
    }
  }

  "SchemaName.toString" should {
    "render as group/name/version" in {
      SchemaName("com.myorg", "page_view", SchemaVersion(1, 0, 0)).toString must
        beEqualTo("com.myorg/page_view/1.0.0")
    }
  }
}
