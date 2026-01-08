package valistrio.core

import org.specs2.mutable.Specification

class ConfigSpec extends Specification {
  "Config.get" should {
    "fail with invalid base64-encoded string" in {
      val in = "notBase64!!!"

      Config.get(in) must beLeft[ValistrioError]
    }
  }
}
