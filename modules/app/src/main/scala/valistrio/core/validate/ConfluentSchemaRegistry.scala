package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.{IO, Resource}
import com.fasterxml.jackson.databind.ObjectMapper
import com.networknt.schema.{JsonSchemaFactory, SpecVersion}
import io.circe.Json
import io.confluent.kafka.schemaregistry.client.{CachedSchemaRegistryClient, SchemaRegistryClient}
import io.confluent.kafka.schemaregistry.client.rest.RestService
import io.confluent.kafka.schemaregistry.client.rest.exceptions.RestClientException
import io.confluent.kafka.schemaregistry.json.JsonSchema
import org.typelevel.log4cats.Logger
import valistrio.core.Config.SchemaRegistryConfig
import valistrio.core.ValistrioError.{ValidateError, ValidationError}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.SchemaName

import java.util.concurrent.TimeoutException
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.Using

object ConfluentSchemaRegistry {

  private val SchemaRegistryCacheCapacity = 2000
  private val mapper                      = new ObjectMapper

  private val networkntFactory =
    JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7)

  /** Creates a [[SchemaRegistry]][IO] backed by a [[CachedSchemaRegistryClient]].
    *
    * On resource acquisition, seeds all Valistrio-owned schemas into the registry.
    * Fails (and thus prevents the app from starting) if the registry is unreachable
    * during seeding.
    *
    * The underlying [[RestService]]'s own connect/read timeouts are set to
    * `config.timeoutMs`, matching the `IO.timeout` wrapped around every call in
    * [[LiveSchemaRegistry]]. Without this, a network partition (broker down, DNS
    * gone) blocks on the JVM's blocking-IO default timeouts (tens of seconds) before
    * `IO.timeout` ever gets a chance to act — cancelling a fiber doesn't interrupt
    * the blocking native call underneath it.
    */
  def resource(config: SchemaRegistryConfig)(implicit logger: Logger[IO]): Resource[IO, SchemaRegistry[IO]] =
    Resource
      .eval(IO {
        val restService = new RestService(config.url)
        restService.setHttpConnectTimeoutMs(config.timeoutMs)
        restService.setHttpReadTimeoutMs(config.timeoutMs)
        new CachedSchemaRegistryClient(restService, SchemaRegistryCacheCapacity)
      })
      .evalMap { client =>
        val registry = new LiveSchemaRegistry(client, config.timeoutMs.millis)
        seedOwnedSchemas(registry).as(registry: SchemaRegistry[IO])
      }

  // ---- Private implementation ----

  private class LiveSchemaRegistry(client: SchemaRegistryClient, timeout: FiniteDuration)
      extends SchemaRegistry[IO] {

    def validate(name: SchemaName, data: Json): IO[Either[ValidateError, Unit]] = {
      val subject = name.toString
      withTimeout(subject, IO.blocking {
        val rawSchema  = client.getLatestSchemaMetadata(subject).getSchema
        val schemaNode = mapper.readTree(rawSchema)
        val schema     = networkntFactory.getSchema(schemaNode)
        val dataNode   = mapper.readTree(data.noSpaces)
        schema.validate(dataNode).asScala.toList
      }).map {
        case Right(Nil) => Right(())
        case Right(msgs) =>
          val errors = NonEmptyList.fromListUnsafe(
            msgs.map(m => ValidationError(m.getPath, m.getMessage))
          )
          Left(ValidationFailed(errors))
        case Left(err) => Left(err)
      }
    }

    def register(name: SchemaName, schemaJson: String): IO[Unit] = {
      val subject = name.toString
      IO.blocking {
        val schema = new JsonSchema(schemaJson)
        client.register(subject, schema)
      }.void
    }

    private def withTimeout[A](subject: String, action: IO[A]): IO[Either[ValidateError, A]] =
      action
        .timeout(timeout)
        .map(Right(_): Either[ValidateError, A])
        .recoverWith {
          case _: TimeoutException =>
            IO.pure(Left(SchemaRegistryTimeout))
          case e: RestClientException =>
            IO.pure(Left(mapRestClientException(e, subject)))
          case e: Exception =>
            IO.pure(Left(SchemaRegistryUnavailable(e.getMessage)))
        }

    private def mapRestClientException(e: RestClientException, subject: String): ValidateError =
      e.getStatus match {
        case 404 => SchemaNotFound(SchemaName.parse(subject).getOrElse(
          // subject is always a valid SchemaName.toString at this point
          throw new IllegalStateException(s"Unparseable subject: $subject")
        ))
        case 408                => SchemaRegistryTimeout
        case 503 | 504          => SchemaRegistryUnavailable(e.getMessage)
        case _                  => SchemaRegistryUnavailable(e.getMessage)
      }
  }

  // ---- Startup seeding ----

  private val OwnedSchemas: List[SchemaName] = List(
    SchemaName.parse("com.valistrio/envelope/1.0.0").getOrElse(
      throw new IllegalStateException("Invalid built-in schema name")
    )
  )

  private def loadSchemaJson(name: SchemaName): IO[String] = {
    val path = s"/schemas/${name.group}/${name.name}/${name.version}.json"
    IO {
      val stream = getClass.getResourceAsStream(path)
      if (stream == null)
        throw new IllegalStateException(s"Built-in schema resource not found: $path")
      Using.resource(stream)(s => new String(s.readAllBytes()))
    }
  }

  private def seedOwnedSchemas(registry: SchemaRegistry[IO])(implicit logger: Logger[IO]): IO[Unit] =
    OwnedSchemas.foldLeft(IO.unit) { (acc, name) =>
      acc.flatMap { _ =>
        for {
          _          <- logger.info(s"Seeding schema: $name")
          schemaJson <- loadSchemaJson(name)
          _          <- registry.register(name, schemaJson)
          _          <- logger.info(s"Schema seeded: $name")
        } yield ()
      }
    }
}
