package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.effect.unsafe.implicits.global
import io.circe.Json
import org.specs2.mutable.Specification
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.ValistrioError.ValidationError
import valistrio.core.domain.SchemaName

class ValidateServiceSpec extends Specification {

  // ---- Stub ----

  /** In-memory SchemaRegistry for testing.
    *
    * Returns `responses(name.toString)` if present, `default` otherwise.
    */
  private class StubSchemaRegistry(
    responses: Map[String, Either[ValidateError, Unit]] = Map.empty,
    default: Either[ValidateError, Unit] = Right(())
  ) extends SchemaRegistry[IO] {
    def validate(name: SchemaName, data: Json): IO[Either[ValidateError, Unit]] =
      IO.pure(responses.getOrElse(name.toString, default))
    def register(name: SchemaName, schemaJson: String): IO[Unit] = IO.unit
  }

  private def stubOk  = new StubSchemaRegistry()
  private def stubFor(subject: String, result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(responses = Map(subject -> result))
  private def stubDefault(result: Either[ValidateError, Unit]) =
    new StubSchemaRegistry(default = result)

  // ---- Fixtures ----

  private val validMeta =
    """{"event_id":"018f1e2a-dead-beef-cafe-000000000000","produced_at":"2026-06-08T12:00:00Z"}"""
  private val validEvent =
    """{"schema":"com.myorg/page_view/1.0.0","data":{"page_url":"https://example.com"}}"""
  private val validContext =
    """{"schema":"com.myorg/user/1.0.0","data":{"user_id":"u-123"}}"""

  private def envelopeData(event: String = validEvent, contexts: Option[String] = None) = {
    val ctxPart = contexts.map(c => s""","contexts":$c""").getOrElse("")
    s"""{"meta":$validMeta,"event":$event$ctxPart}"""
  }

  private def envelopeJson(data: String) =
    s"""{"schema":"com.valistrio/envelope/1.0.0","data":$data}"""

  private val validEnvelope            = envelopeJson(envelopeData())
  private val validEnvelopeWithContext  = envelopeJson(envelopeData(contexts = Some(s"[$validContext]")))
  private val validEnvelopeTwoContexts  = envelopeJson(envelopeData(contexts = Some(s"[$validContext,$validContext]")))

  private val eventSubject   = "com.myorg/page_view/1.0.0"
  private val contextSubject = "com.myorg/user/1.0.0"
  private val envelopeSubject = ValidateService.EnvelopeSchemaName.toString

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

    "return Success for a valid envelope without contexts" in {
      val svc = new ValidateService(stubOk)
      run(svc, validEnvelope) must beEqualTo(ValidateResponse.Success)
    }

    "return Success for a valid envelope with one context" in {
      val svc = new ValidateService(stubOk)
      run(svc, validEnvelopeWithContext) must beEqualTo(ValidateResponse.Success)
    }

    "return Success for a valid envelope with multiple contexts" in {
      val svc = new ValidateService(stubOk)
      run(svc, validEnvelopeTwoContexts) must beEqualTo(ValidateResponse.Success)
    }

    // -- Decode failures (short-circuit) --

    "return Failure(malformed_json) when body is not valid JSON" in {
      val svc = new ValidateService(stubOk)
      val resp = run(svc, "not json at all")
      errorTypes(resp) must beEqualTo(List("malformed_json"))
    }

    "return Failure(structural_decode_error) when envelope has extra fields" in {
      val badEnvelope = """{"schema":"com.valistrio/envelope/1.0.0","data":{},"extra":"field"}"""
      val svc = new ValidateService(stubOk)
      errorTypes(run(svc, badEnvelope)) must beEqualTo(List("structural_decode_error"))
    }

    "return Failure(structural_decode_error) when envelope data is missing required fields" in {
      val badEnvelope = envelopeJson("""{"meta":{}}""")
      val svc = new ValidateService(stubOk)
      errorTypes(run(svc, badEnvelope)) must beEqualTo(List("structural_decode_error"))
    }

    "return Failure(structural_decode_error) when event data is not an object" in {
      val badEvent  = """{"schema":"com.myorg/page_view/1.0.0","data":"not-an-object"}"""
      val badBody   = envelopeJson(envelopeData(event = badEvent))
      val svc = new ValidateService(stubOk)
      errorTypes(run(svc, badBody)) must beEqualTo(List("structural_decode_error"))
    }

    // -- Registry errors --

    "return Failure(schema_not_found) when event schema is not in the registry" in {
      val svc = new ValidateService(stubFor(eventSubject, Left(SchemaNotFound(
        SchemaName.parse(eventSubject).toOption.get
      ))))
      errorTypes(run(svc, validEnvelope)) must contain("schema_not_found")
    }

    "return Failure(schema_registry_timeout) when registry times out" in {
      val svc = new ValidateService(stubDefault(Left(SchemaRegistryTimeout)))
      errorTypes(run(svc, validEnvelope)) must contain("schema_registry_timeout")
    }

    "return Failure(schema_registry_unavailable) when registry is unreachable" in {
      val svc = new ValidateService(stubDefault(Left(SchemaRegistryUnavailable("connection refused"))))
      errorTypes(run(svc, validEnvelope)) must contain("schema_registry_unavailable")
    }

    // -- Validation failures --

    "return Failure(schema_validation_failed) when event data fails schema validation" in {
      val validationErr = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "must be a string")))
      val svc = new ValidateService(stubFor(eventSubject, Left(validationErr)))
      val resp = run(svc, validEnvelope)
      errorTypes(resp) must beEqualTo(List("schema_validation_failed"))
    }

    "expand ValidationFailed into one error entry per field violation" in {
      val validationErr = ValidationFailed(NonEmptyList.of(
        ValidationError("$.field_a", "error A"),
        ValidationError("$.field_b", "error B")
      ))
      val svc = new ValidateService(stubFor(eventSubject, Left(validationErr)))
      val resp = run(svc, validEnvelope)
      errorTypes(resp) must beEqualTo(List("schema_validation_failed", "schema_validation_failed"))
    }

    "return Failure with a context validation error" in {
      val validationErr = ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required")))
      val svc = new ValidateService(stubFor(contextSubject, Left(validationErr)))
      errorTypes(run(svc, validEnvelopeWithContext)) must contain("schema_validation_failed")
    }

    // -- Error collection (no short-circuit after decode) --

    "collect errors from both event and context validations" in {
      val eventErr   = ValidationFailed(NonEmptyList.one(ValidationError("$.page_url", "bad")))
      val contextErr = ValidationFailed(NonEmptyList.one(ValidationError("$.user_id", "required")))
      val stub = new StubSchemaRegistry(responses = Map(
        eventSubject   -> Left(eventErr),
        contextSubject -> Left(contextErr)
      ))
      val svc  = new ValidateService(stub)
      val resp = run(svc, validEnvelopeWithContext)
      // Two validation errors — one from event, one from context
      errorTypes(resp).count(_ == "schema_validation_failed") must beEqualTo(2)
    }

    "collect errors from envelope validation and event validation simultaneously" in {
      val envelopeErr = SchemaNotFound(ValidateService.EnvelopeSchemaName)
      val eventErr    = SchemaNotFound(SchemaName.parse(eventSubject).toOption.get)
      val stub = new StubSchemaRegistry(responses = Map(
        envelopeSubject -> Left(envelopeErr),
        eventSubject    -> Left(eventErr)
      ))
      val svc  = new ValidateService(stub)
      val resp = run(svc, validEnvelope)
      errorTypes(resp).count(_ == "schema_not_found") must beEqualTo(2)
    }
  }
}
