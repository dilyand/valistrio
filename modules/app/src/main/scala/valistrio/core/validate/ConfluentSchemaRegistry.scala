package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.foldable._
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}
import fs2.text
import io.circe.Json
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.RestService
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.schemaregistry.json.JsonSchema
import org.typelevel.log4cats.Logger
import valistrio.core.Config.SchemaRegistryConfig
import valistrio.core.ValistrioError.{ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{SchemaRef, SchemaVersion}

import java.io.IOException
import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

object ConfluentSchemaRegistry {

  private val SchemaRegistryCacheCapacity = 2000
  private val mapper                      = new ObjectMapper
  private val jsonSchemaFactory           = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)

  /** Creates a [[SchemaRegistry]] backed by a [[CachedSchemaRegistryClient]].
    *
    * On resource acquisition, seeds all Valistrio-owned schemas and fails (preventing
    * startup) if the registry is unreachable during seeding.
    *
    * The [[RestService]]'s own connect/read timeouts are set to `config.timeoutMs`,
    * matching the `IO.timeout` around every call: cancelling a fiber does not interrupt
    * a blocking native call underneath it, so without the client-level timeout a network
    * partition would block on the JVM's default blocking-IO timeout (tens of seconds)
    * before `IO.timeout` could act.
    */
  def resource(config: SchemaRegistryConfig)(implicit logger: Logger[IO]): Resource[IO, SchemaRegistry] =
    Resource.fromAutoCloseable(IO {
      val restService = new RestService(config.url)
      restService.setHttpConnectTimeoutMs(config.timeoutMs)
      restService.setHttpReadTimeoutMs(config.timeoutMs)
      new CachedSchemaRegistryClient(restService, SchemaRegistryCacheCapacity)
    }).evalMap { client =>
      val registry = confluentRegistry(client, config.timeoutMs.millis)
      seedOwnedSchemas(registry).as(registry)
    }

  private def confluentRegistry(client: SchemaRegistryClient, timeout: FiniteDuration): SchemaRegistry =
    new SchemaRegistry {
      def validate(name: SchemaRef, data: Json): IO[Either[ValidateError, Unit]] =
        withTimeout(name, timeout, IO.blocking {
          val rawSchema  = client.getLatestSchemaMetadata(name.toString).getSchema
          val schemaNode = mapper.readTree(rawSchema)
          val schema     = jsonSchemaFactory.getSchema(schemaNode)
          val dataNode   = mapper.readTree(data.noSpaces)
          schema.validate(dataNode).asScala.toList
        }).map {
          case Left(err) => Left(err)
          case Right(messages) =>
            NonEmptyList.fromList(messages.map(m => ValidationError(m.getPath, m.getMessage))) match {
              case None      => Right(())
              case Some(nel) => Left(ValidationFailed(nel))
            }
        }

      def register(name: SchemaRef, schemaJson: String): IO[Unit] =
        IO.blocking(client.register(name.toString, new JsonSchema(schemaJson))).void
    }

  private def withTimeout[A](name: SchemaRef, timeout: FiniteDuration, action: IO[A]): IO[Either[ValidateError, A]] =
    action
      .timeout(timeout)
      .map(Right(_): Either[ValidateError, A])
      .recoverWith {
        case _: TimeoutException    => IO.pure(Left(SchemaRegistryTimeout))
        case e: RestClientException => IO.pure(Left(mapRestClientException(e, name)))
        case e: IOException         => IO.pure(Left(SchemaRegistryUnavailable(e.getMessage)))
      }

  private def mapRestClientException(e: RestClientException, name: SchemaRef): ValidateError =
    e.getStatus match {
      case 404       => SchemaNotFound(name)
      case 408       => SchemaRegistryTimeout
      case 503 | 504 => SchemaRegistryUnavailable(e.getMessage)
      case _         => SchemaRegistryUnavailable(e.getMessage)
    }

  // ---- Startup seeding ----

  private val OwnedSchemas: List[SchemaRef] = List(
    SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))
  )

  private def loadSchemaJson(name: SchemaRef): IO[String] =
    fs2.io
      .readClassLoaderResource[IO](s"schemas/${name.group}/${name.name}/${name.version}.json")
      .through(text.utf8.decode)
      .compile
      .string

  private def seedOwnedSchemas(registry: SchemaRegistry)(implicit logger: Logger[IO]): IO[Unit] =
    OwnedSchemas.traverse_ { name =>
      for {
        _          <- logger.info(s"Seeding schema: $name")
        schemaJson <- loadSchemaJson(name)
        _          <- registry.register(name, schemaJson)
        _          <- logger.info(s"Schema seeded: $name")
      } yield ()
    }
}
