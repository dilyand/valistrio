package valistrio.core

import valistrio.fixtures.TestCase._
import org.specs2.mutable.Specification
import valistrio.TestUtils.b64

class ReferenceConfSpec extends Specification {
  "Config.get" should {
    "successfully parse the reference.conf HOCON" in {
      val in       = b64(ReferenceConf.in)
      val expected = ReferenceConf.expected

      Config.get(in) must beRight(expected)
    }
  }
}
