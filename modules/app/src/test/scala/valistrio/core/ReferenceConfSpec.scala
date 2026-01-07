package valistrio.core

import valistrio.fixtures._
import org.specs2.mutable.Specification

class ReferenceConfSpec extends Specification {
  "Config.get" should {
    "successfully parse the reference.conf HOCON" in {
      val in       = base64EncodedReferenceConf
      val expected = parsedReferenceConf

      Config.get(in) must beRight(expected)
    }
  }
}
