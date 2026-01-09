package valistrio.core

import valistrio.fixtures._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ConfigError
import valistrio.core.ValistrioError.ConfigError.{NotBase64, TypesafeConfigError}

class ConfigSpec extends Specification {
  "Config.get" should {
    "fail with invalid base64-encoded string" in {
      val in = "notBase64!!!"

      Config.get(in) must beLeft(beLike[ConfigError] { case NotBase64(_) => ok })
    }

    "fail with malformed HOCON" in {
      val in = base64EncodedMalformedHocon

      Config.get(in) must beLeft(beLike[ConfigError] { case TypesafeConfigError(_) => ok })
    }
  }
}
