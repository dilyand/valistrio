package valistrio.core.adapters.snowplow

import org.specs2.mutable.Specification

class IgluSchemaSpec extends Specification {

  "IgluSchema.toSchemaRef" should {

    "translate a well-formed iglu URI to a valistrio schema ref" in {
      IgluSchema
        .toSchemaRef("iglu:com.askattest.demo/survey_create/jsonschema/1-0-0")
        .map(_.toString) must beRight("com.askattest.demo/survey_create/1.0.0")
    }

    "carry the SchemaVer integers across into major.minor.patch" in {
      IgluSchema.toSchemaRef("iglu:com.acme/thing/jsonschema/2-3-4").map(_.toString) must beRight("com.acme/thing/2.3.4")
    }

    "reject a URI without the iglu scheme" in {
      IgluSchema.toSchemaRef("com.acme/thing/jsonschema/1-0-0") must beLeft
    }

    "reject a URI missing the jsonschema format segment" in {
      IgluSchema.toSchemaRef("iglu:com.acme/thing/1-0-0") must beLeft
    }

    "reject a URI whose SchemaVer is not three integers" in {
      IgluSchema.toSchemaRef("iglu:com.acme/thing/jsonschema/1-0") must beLeft
    }
  }
}
