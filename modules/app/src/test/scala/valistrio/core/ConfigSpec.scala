package valistrio.core

import valistrio.fixtures.TestCase._
import valistrio.TestUtils._
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ConfigError
import valistrio.core.ValistrioError.ConfigError.ParsingError

class ConfigSpec extends Specification {
  "Config.get" should {
    "fail with invalid base64-encoded string" in {
      val in       = NB64.in
      val expected = NB64.expected

      Config.get(in) must beLeft(beLike[ConfigError](expected))
    }

    "fail with malformed HOCON" in {
      val in       = b64(MalformedHocon.in)
      val expected = MalformedHocon.expected

      Config.get(in) must beLeft(beLike[ConfigError](expected))
    }

    "ignore non-namespaced Valistrio keys in user-supplied HOCON" in {
      val in       = b64(NonNamespacedHocon.in)
      val expected = NonNamespacedHocon.expected

      Config.get(in) must beRight(beLike[Config] { case c: Config =>
        c mustEqual expected
      })
    }

    "user-supplied HOCON overrides application.conf" in {
      val in       = b64(OverridePortOnlyHocon.in)
      val expected = OverridePortOnlyHocon.expected

      Config.get(in) must beRight(beLike[Config] { case c: Config =>
        c mustEqual expected
      })
    }

    "user-supplied HOCON overrides schemaRegistry.url" in {
      val in       = b64(OverrideSchemaRegistryUrlHocon.in)
      val expected = OverrideSchemaRegistryUrlHocon.expected

      Config.get(in) must beRight(beLike[Config] { case c: Config =>
        c mustEqual expected
      })
    }

    "reject non-finite durations such as Inf" in {
      val in       = b64(OverrideDurationWithInfHocon.in)
      val expected = OverrideDurationWithInfHocon.expected

      Config.get(in) must beLeft(beLike[ConfigError] { case ParsingError(msg) =>
        msg must contain(expected)
      })
    }
  }
}
