package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import cats.syntax.applicativeError._
import cats.syntax.flatMap._
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

/** A [[SchemaRegistry]] backed by a [[CachedSchemaRegistryClient]]. Failures are raised as a
  * [[ValidateError]] on the IO error channel (see [[withTimeout]]).
  */
final class ConfluentSchemaRegistry private (client: SchemaRegistryClient, timeout: FiniteDuration)
    extends SchemaRegistry {

  import ConfluentSchemaRegistry._

  def validate(ref: SchemaRef, data: Json): IO[Unit] =
    withTimeout(ref, timeout, IO.blocking {
      val rawSchema  = client.getLatestSchemaMetadata(ref.toString).getSchema
      val schemaNode = mapper.readTree(rawSchema)
      val schema     = jsonSchemaFactory.getSchema(schemaNode)
      val dataNode   = mapper.readTree(data.noSpaces)
      schema.validate(dataNode).asScala.toList
    }).flatMap { messages =>
      NonEmptyList.fromList(messages.map(m => ValidationError(m.getPath, m.getMessage))) match {
        case None      => IO.unit
        case Some(nel) => IO.raiseError(ValidationFailed(nel))
      }
    }

  def register(ref: SchemaRef, schemaJson: String): IO[Unit] =
    IO.blocking(client.register(ref.toString, new JsonSchema(schemaJson))).void
}

object ConfluentSchemaRegistry {

  private val mapper            = new ObjectMapper
  private val jsonSchemaFactory           = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)

  /** On acquisition, seeds all Valistrio-owned schemas and fails (preventing startup) if the
    * registry is unreachable during seeding.
    *
    * The [[RestService]]'s own connect/read timeouts are set to `config.timeoutMs`, matching the
    * `IO.timeout` around every call: cancelling a fiber does not interrupt a blocking native call
    * underneath it, so without the client-level timeout a network partition would block on the
    * JVM's default blocking-IO timeout before `IO.timeout` could act.
    */
  def resource(config: SchemaRegistryConfig)(implicit logger: Logger[IO]): Resource[IO, SchemaRegistry] =
    Resource.fromAutoCloseable(IO {
      val restService = new RestService(config.url)
      restService.setHttpConnectTimeoutMs(config.timeoutMs)
      restService.setHttpReadTimeoutMs(config.timeoutMs)
      new CachedSchemaRegistryClient(restService, config.cacheCapacity)
    }).evalMap { client =>
      val registry = new ConfluentSchemaRegistry(client, config.timeoutMs.millis)
      seedOwnedSchemas(registry).as(registry: SchemaRegistry)
    }

  private def withTimeout[A](ref: SchemaRef, timeout: FiniteDuration, action: IO[A]): IO[A] =
    action.timeout(timeout).adaptError {
      case _: TimeoutException    => SchemaRegistryTimeout
      case e: RestClientException => mapRestClientException(e, ref)
      case e: IOException         => SchemaRegistryUnavailable(e.getMessage)
    }

  private def mapRestClientException(e: RestClientException, ref: SchemaRef): ValidateError =
    e.getStatus match {
      case 404       => SchemaNotFound(ref)
      case 408       => SchemaRegistryTimeout
      case 503 | 504 => SchemaRegistryUnavailable(e.getMessage)
      case _         => SchemaRegistryUnavailable(e.getMessage)
    }

  // ---- Startup seeding ----

  private[validate] val OwnedSchemas: List[SchemaRef] = List(
    SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))
  )

  private[validate] def loadSchemaJson(ref: SchemaRef): IO[String] =
    fs2.io
      .readClassLoaderResource[IO](s"schemas/${ref.group}/${ref.name}/${ref.version}.json")
      .through(text.utf8.decode)
      .compile
      .string

  private def seedOwnedSchemas(registry: SchemaRegistry)(implicit logger: Logger[IO]): IO[Unit] =
    OwnedSchemas.traverse_ { ref =>
      logger.info(s"Seeding schema: $ref") >>
        loadSchemaJson(ref).flatMap(registry.register(ref, _)) >>
        logger.info(s"Schema seeded: $ref")
    }
}
