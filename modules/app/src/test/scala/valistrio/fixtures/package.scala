package valistrio

import org.specs2.matcher.MatchResult
import org.specs2.matcher.MustThrownMatchers.ok
import valistrio.TestUtils._
import valistrio.core.Config
import valistrio.core.Config.{SchemaRegistryConfig, ServerConfig}
import valistrio.core.ValistrioError.ConfigError
import valistrio.core.ValistrioError.ConfigError.{NotBase64, TypesafeConfigError}

import scala.concurrent.duration.DurationInt

package object fixtures {
  sealed trait TestCase[I, E] {
    val in: I
    val expected: E
  }

  final object TestCase {
    private val defaultSchemaRegistry = SchemaRegistryConfig("http://localhost:8081", 3000)

    final object ReferenceConf extends TestCase[String, Config] {
      val in: String = fromFile("config/reference.conf")
      val expected   = Config(ServerConfig("0.0.0.0", 8080, 2097152L, 5.seconds), defaultSchemaRegistry)
    }

    final object NB64 extends TestCase[String, PartialFunction[ConfigError, MatchResult[_]]] {
      val in: String = "notBase64!!!"
      val expected: PartialFunction[ConfigError, MatchResult[_]] = { case NotBase64(_) =>
        ok
      }
    }

    final object MalformedHocon extends TestCase[String, PartialFunction[ConfigError, MatchResult[_]]] {
      val in: String = ReferenceConf.in + "}"
      val expected: PartialFunction[ConfigError, MatchResult[_]] = { case TypesafeConfigError(_) =>
        ok
      }
    }

    final object NonNamespacedHocon extends TestCase[String, Config] {
      val in: String =
        """
          |server {
          |  host = "127.0.0.1"
          |  port = 9001
          |  maxBytes = 1234
          |  requestTimeout = 3s
          |}
          |""".stripMargin

      // Should be ignored in favour of application.conf
      val expected = Config(ServerConfig("0.0.0.0", 8080, 2097152L, 5.seconds), defaultSchemaRegistry)
    }

    final object OverridePortOnlyHocon extends TestCase[String, Config] {
      val in: String =
        """
          |valistrio {
          |  server {
          |    port = 9999
          |  }
          |}
          |""".stripMargin

      // Only port should differ from application.conf
      val expected = Config(ServerConfig("0.0.0.0", 9999, 2097152L, 5.seconds), defaultSchemaRegistry)
    }

    final object OverrideSchemaRegistryUrlHocon extends TestCase[String, Config] {
      val in: String =
        """
          |valistrio {
          |  schemaRegistry {
          |    url = "http://registry.internal:8081"
          |  }
          |}
          |""".stripMargin

      val expected = Config(
        ServerConfig("0.0.0.0", 8080, 2097152L, 5.seconds),
        SchemaRegistryConfig("http://registry.internal:8081", 3000)
      )
    }

    final object OverrideDurationWithInfHocon extends TestCase[String, String] {
      val in: String =
        """
          |valistrio {
          |  server {
          |    requestTimeout = Inf
          |  }
          |}
          |""".stripMargin

      val expected: String = "non-finite"
    }
  }
}
