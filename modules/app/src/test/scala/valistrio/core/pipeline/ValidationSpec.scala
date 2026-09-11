package valistrio.core.pipeline

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.applicativeError._
import io.circe.{Json, parser}
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.{ValidateError, ValidationErrors}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.{ResponseError, SchemaRef, ValidatedEvent}
import valistrio.core.resources.SchemaRegistry

class ValidationSpec extends Specification {

  // ---- Stub ----

  /** In-memory SchemaRegistry: returns `responses(ref.toString)` if present, else `default`.
    * Structure is not really validated here (see the integration suite) — the event-schema
    * subject just returns `default`, so fixtures must be well-formed for extraction to succeed.
    */
  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry {
    def validate(ref: SchemaRef, data: Json): IO[Unit] =
      IO.fromEither(responses.getOrElse(ref.toString, default))
    def register(ref: SchemaRef, schemaJson: String): IO[Unit] = IO.unit
  }

  private def stubOk = new StubSchemaRegistry()
  private def stubFor(subject: String, result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(responses = Map(subject -> result))
  private def stubDefault(result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(default = result)

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validBody =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""
  private val validContext =
    """{"schema":"com.myorg/user/1.0.0","data":{"user_id":"u-123"}}"""

  private def eventData(body: String = validBody, contexts: Option[String] = None) = {
    val ctxPart = contexts.map(c => s""","contexts":$c""").getOrElse("")
    s"""{"meta":$validMeta,"body":$body$ctxPart}"""
  }
  private def eventJson(data: String): Json =
    parser.parse(s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":$data}""").toOption.get

  private val validEvent            = eventJson(eventData())
  private val validEventWithContext = eventJson(eventData(contexts = Some(s"[$validContext]")))
  private val validEventTwoContexts = eventJson(eventData(contexts = Some(s"[$validContext,$validContext]")))

  private val bodySubject    = "com.myorg/page_view/1.0.0"
  private val contextSubject = "com.myorg/user/1.0.0"

  // ---- Helpers ----

  private def validate(registry: SchemaRegistry, json: Json): Either[ValidationErrors, ValidatedEvent] =
    new Validation(registry).validate(json).attemptNarrow[ValidationErrors].unsafeRunSync()

  private def errorTypes(registry: SchemaRegistry, json: Json): List[String] =
    validate(registry, json) match {
      case Right(_)                     => Nil
      case Left(ValidationErrors(errs)) => errs.flatMap(ResponseError.from).toList.map(_.`type`)
    }

  // ---- Tests ----

  "Validation" should {

    // -- Happy path --

    "yield a ValidatedEvent for a valid event without contexts" in {
      validate(stubOk, validEvent) must beRight
    }

    "yield a ValidatedEvent for a valid event with one context" in {
      validate(stubOk, validEventWithContext) must beRight
    }

    "yield a ValidatedEvent for a valid event with multiple contexts" in {
      validate(stubOk, validEventTwoContexts) must beRight
    }

    // -- Registry errors --

    "raise schema_not_found when the body schema is not in the registry" in {
      val stub = stubFor(bodySubject, Left(SchemaNotFound(SchemaRef.parse(bodySubject).toOption.get)))
      errorTypes(stub, validEvent) must contain("schema_not_found")
    }

    "raise schema_registry_timeout when the registry times out" in {
      errorTypes(stubDefault(Left(SchemaRegistryTimeout)), validEvent) must contain("schema_registry_timeout")
    }

    "raise schema_registry_unavailable when the registry is unreachable" in {
      errorTypes(stubDefault(Left(SchemaRegistryUnavailable("connection refused"))), validEvent) must contain("schema_registry_unavailable")
    }

    // -- Validation failures --

    "raise schema_validation_failed when the body fails schema validation" in {
      val err = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      errorTypes(stubFor(bodySubject, Left(err)), validEvent) must beEqualTo(List("schema_validation_failed"))
    }

    "expand ValidationFailed into one entry per field violation" in {
      val err = ValidationFailed(NonEmptyList.of(ValidationError("$.a", "A"), ValidationError("$.b", "B")))
      errorTypes(stubFor(bodySubject, Left(err)), validEvent) must beEqualTo(List("schema_validation_failed", "schema_validation_failed"))
    }

    "surface a context validation error" in {
      val err = ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required")))
      errorTypes(stubFor(contextSubject, Left(err)), validEventWithContext) must contain("schema_validation_failed")
    }

    // -- Error collection across payloads (no short-circuit) --

    "collect errors from both the body and a context" in {
      val stub = new StubSchemaRegistry(responses = Map(
        bodySubject    -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "bad")))),
        contextSubject -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required"))))
      ))
      errorTypes(stub, validEventWithContext).count(_ == "schema_validation_failed") must beEqualTo(2)
    }

    // -- Structural gate short-circuits payload validation --

    "short-circuit on an event-schema failure without validating payloads" in {
      val stub = stubFor(Validation.EventSchemaRef.toString, Left(SchemaRegistryTimeout))
      errorTypes(stub, validEvent) must beEqualTo(List("schema_registry_timeout"))
    }
  }

  "Validation.parse" should {

    "return the JSON for a well-formed body" in {
      Validation.parse(validBody) must beRight
    }

    "return MalformedJson for a non-JSON body" in {
      Validation.parse("not json at all") must beLeft.like { case MalformedJson(_) => ok }
    }
  }
}
