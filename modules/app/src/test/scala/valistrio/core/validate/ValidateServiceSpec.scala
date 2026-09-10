package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.SchemaRef

class ValidateServiceSpec extends Specification {

  // ---- Stub ----

  /** In-memory SchemaRegistry: returns `responses(name.toString)` if present, else `default`.
    * Structure is not really validated here (see the integration suite) — the event-schema
    * subject just returns `default`, so fixtures must be well-formed for extraction to succeed.
    */
  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry {
    def validate(name: SchemaRef, data: Json): IO[Either[ValidateError, Unit]] =
      IO.pure(responses.getOrElse(name.toString, default))
    def register(name: SchemaRef, schemaJson: String): IO[Unit] = IO.unit
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
  private def eventJson(data: String) =
    s"""{"schema":"io.github.dilyand.valistrio/event/1.0.0","data":$data}"""

  private val validEvent            = eventJson(eventData())
  private val validEventWithContext = eventJson(eventData(contexts = Some(s"[$validContext]")))
  private val validEventTwoContexts = eventJson(eventData(contexts = Some(s"[$validContext,$validContext]")))

  private val bodySubject    = "com.myorg/page_view/1.0.0"
  private val contextSubject = "com.myorg/user/1.0.0"

  // ---- Helpers ----

  private def run(service: ValidateService, body: String) =
    service.validate(body).unsafeRunSync()

  private def errorTypes(resp: ValidateResponse): List[String] = resp match {
    case ValidateResponse.Failure(errors) => errors.toList.map(_.`type`)
    case ValidateResponse.Success         => Nil
  }

  // ---- Tests ----

  "ValidateService" should {

    // -- Happy path --

    "return Success for a valid event without contexts" in {
      run(new ValidateService(stubOk), validEvent) must beEqualTo(ValidateResponse.Success)
    }

    "return Success for a valid event with one context" in {
      run(new ValidateService(stubOk), validEventWithContext) must beEqualTo(ValidateResponse.Success)
    }

    "return Success for a valid event with multiple contexts" in {
      run(new ValidateService(stubOk), validEventTwoContexts) must beEqualTo(ValidateResponse.Success)
    }

    // -- Parse failure (short-circuit) --

    "return Failure(malformed_json) when the body is not valid JSON" in {
      errorTypes(run(new ValidateService(stubOk), "not json at all")) must beEqualTo(List("malformed_json"))
    }

    // -- Registry errors --

    "return Failure(schema_not_found) when the body schema is not in the registry" in {
      val svc = new ValidateService(stubFor(bodySubject, Left(SchemaNotFound(SchemaRef.parse(bodySubject).toOption.get))))
      errorTypes(run(svc, validEvent)) must contain("schema_not_found")
    }

    "return Failure(schema_registry_timeout) when the registry times out" in {
      errorTypes(run(new ValidateService(stubDefault(Left(SchemaRegistryTimeout))), validEvent)) must contain("schema_registry_timeout")
    }

    "return Failure(schema_registry_unavailable) when the registry is unreachable" in {
      errorTypes(run(new ValidateService(stubDefault(Left(SchemaRegistryUnavailable("connection refused")))), validEvent)) must contain("schema_registry_unavailable")
    }

    // -- Validation failures --

    "return Failure(schema_validation_failed) when the body fails schema validation" in {
      val err = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      errorTypes(run(new ValidateService(stubFor(bodySubject, Left(err))), validEvent)) must beEqualTo(List("schema_validation_failed"))
    }

    "expand ValidationFailed into one entry per field violation" in {
      val err = ValidationFailed(NonEmptyList.of(ValidationError("$.a", "A"), ValidationError("$.b", "B")))
      errorTypes(run(new ValidateService(stubFor(bodySubject, Left(err))), validEvent)) must beEqualTo(List("schema_validation_failed", "schema_validation_failed"))
    }

    "return a context validation error" in {
      val err = ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required")))
      errorTypes(run(new ValidateService(stubFor(contextSubject, Left(err))), validEventWithContext)) must contain("schema_validation_failed")
    }

    // -- Error collection across payloads (no short-circuit) --

    "collect errors from both the body and a context" in {
      val stub = new StubSchemaRegistry(responses = Map(
        bodySubject    -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "bad")))),
        contextSubject -> Left(ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required"))))
      ))
      errorTypes(run(new ValidateService(stub), validEventWithContext)).count(_ == "schema_validation_failed") must beEqualTo(2)
    }

    // -- Structural gate short-circuits payload validation --

    "short-circuit on an event-schema failure without validating payloads" in {
      val stub = stubFor(ValidateService.EventSchemaRef.toString, Left(SchemaRegistryTimeout))
      errorTypes(run(new ValidateService(stub), validEvent)) must beEqualTo(List("schema_registry_timeout"))
    }
  }
}
