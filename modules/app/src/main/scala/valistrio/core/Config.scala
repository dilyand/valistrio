package valistrio.core

import cats.effect.IO
import cats.implicits._
import io.circe.Decoder
import io.circe.generic.semiauto.deriveDecoder
import io.circe.config.parser
import com.typesafe.config.{ConfigException, ConfigFactory, Config => TypesafeConfig}
import valistrio.core.Config._
import valistrio.core.ValistrioError.ConfigParsingError

import java.nio.charset.StandardCharsets
import java.util.Base64
import scala.concurrent.duration.{Duration, FiniteDuration}

final case class Config(server: ServerConfig)

object Config {
  implicit val configDecoder: Decoder[Config] = deriveDecoder[Config]

  implicit val finiteDurationDecoder: Decoder[FiniteDuration] =
    Decoder[String].emap { s =>
      Either.catchNonFatal(Duration(s)).leftMap(_.getMessage).flatMap {
        case fd: FiniteDuration => Right(fd)
        case other =>
          Left(
            s"""Duration value '$s' resolved to a non-finite duration ($other).
           Finite duration required. Examples: '5 seconds', '30s', '1 minute', '500 millis'.
           """.stripMargin
          )
      }
    }

  final case class ServerConfig(host: String, port: Int, maxBytes: Long, requestTimeout: FiniteDuration)
  object ServerConfig {
    implicit val serverConfigDecoder: Decoder[ServerConfig] = deriveDecoder[ServerConfig]
  }

  private val base64             = Base64.getDecoder
  private val Namespace          = "valistrio"
  val ValistrioConfigVar: String = "VALISTRIO_CONFIG"

  def make: IO[Config] = get(sys.env.getOrElse(ValistrioConfigVar, "")) match {
    case Right(c) => IO.pure(c)
    case Left(err) => IO.raiseError(err)
  }

  private[core] def get(encodedStr: String): Either[ValistrioError, Config] = {
    val result = for {
      bytes <- Either.catchOnly[IllegalArgumentException](base64.decode(encodedStr)).leftMap(_.getMessage)
      config <-
        Either
          .catchOnly[ConfigException](ConfigFactory.parseString(new String(bytes, StandardCharsets.UTF_8)))
          .leftMap(_.getMessage)
      parsed <- parse(config)
    } yield parsed

    result.leftMap(e => ConfigParsingError(e))
  }

  /** Parses the given HOCON config using the standard Typesafe Config layering model,
    * while allowing user-provided configuration to override defaults.
    *
    * Effective precedence (highest wins):
    *
    *  1. System properties
    *  2. Values provided in the supplied configuration HOCON
    *  3. `application.conf` of this application
    *  4. `reference.conf` of this application and any dependent libraries
    */
  private def parse(hocon: TypesafeConfig): Either[String, Config] = {
    val sys = ConfigFactory.defaultOverrides() // system properties
    val defaults = ConfigFactory
      .defaultApplication()                           // application.conf
      .withFallback(ConfigFactory.defaultReference()) // reference.conf

    val merged = namespaced(sys.withFallback(hocon).withFallback(defaults))

    parser.decode[Config](merged).leftMap(_.show)
  }

  /** Optionally give precedence to configs wrapped in a "valistrio" block, to help avoid polluting the config namespace */
  private def namespaced(config: TypesafeConfig): TypesafeConfig =
    if (config.hasPath(Namespace)) config.getConfig(Namespace).withFallback(config.withoutPath(Namespace))
    else config
}
