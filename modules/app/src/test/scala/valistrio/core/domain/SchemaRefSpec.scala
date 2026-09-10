package valistrio.core.domain

import org.specs2.mutable.Specification

class SchemaRefSpec extends Specification {
  "SchemaRef.parse" should {
    "parse a valid schema name" in {
      SchemaRef.parse("com.myorg/page_view/1.0.0") must beRight(
        SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0))
      )
    }

    "parse a Valistrio-owned schema name" in {
      SchemaRef.parse("com.valistrio/envelope/1.0.0") must beRight(
        SchemaRef("com.valistrio", "envelope", SchemaVersion(1, 0, 0))
      )
    }

    "parse a single-segment group" in {
      SchemaRef.parse("myorg/user/2.1.0") must beRight(
        SchemaRef("myorg", "user", SchemaVersion(2, 1, 0))
      )
    }

    "parse a simple (single-word) name" in {
      SchemaRef.parse("com.example/user/1.0.0") must beRight(
        SchemaRef("com.example", "user", SchemaVersion(1, 0, 0))
      )
    }

    "reject an empty string" in {
      SchemaRef.parse("") must beLeft
    }

    "reject too few separators (no slash)" in {
      SchemaRef.parse("com.myorg") must beLeft
    }

    "reject too few separators (one slash)" in {
      SchemaRef.parse("com.myorg/page_view") must beLeft
    }

    "reject too many separators (three slashes)" in {
      SchemaRef.parse("com.myorg/page_view/1.0.0/extra") must beLeft
    }

    "reject an uppercase group" in {
      SchemaRef.parse("Com.myorg/page_view/1.0.0") must beLeft
    }

    "reject a group with empty segment" in {
      SchemaRef.parse("com..myorg/page_view/1.0.0") must beLeft
    }

    "reject a group starting with a digit" in {
      SchemaRef.parse("1com.myorg/page_view/1.0.0") must beLeft
    }

    "reject an uppercase name" in {
      SchemaRef.parse("com.myorg/PageView/1.0.0") must beLeft
    }

    "reject a name with hyphens" in {
      SchemaRef.parse("com.myorg/page-view/1.0.0") must beLeft
    }

    "reject a name starting with underscore" in {
      SchemaRef.parse("com.myorg/_page_view/1.0.0") must beLeft
    }

    "reject an invalid version" in {
      SchemaRef.parse("com.myorg/page_view/1.0") must beLeft
    }
  }

  "SchemaRef.toString" should {
    "render as group/name/version" in {
      SchemaRef("com.myorg", "page_view", SchemaVersion(1, 0, 0)).toString must
        beEqualTo("com.myorg/page_view/1.0.0")
    }
  }
}
