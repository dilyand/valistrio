package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.applicativeError._
import cats.syntax.parallel._
import io.circe.{Json, parser}
import valistrio.core.ValistrioError.{ValidateError, ValidationErrors}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{Event, ResponseError, SchemaRef, SchemaVersion, TypedData, ValidatedEvent}
import valistrio.core.resources.SchemaRegistry

/** Orchestrates validation for /validate and /post.
  *
  * Multi-pass parse (the event schema is the sole structural authority):
  *  1. parse the body as JSON — [[MalformedJson]] on failure (the only offline check)
  *  2. validate the JSON against the event schema — structural, format and ref-shape
  *     failures all surface as [[ValidationFailed]]
  *  3. extract the navigable [[Event]] — total after (2); a failure is an internal bug
  *  4. validate `body` and each context against their own schemas, collecting all errors
  *  5. on success, produce a [[ValidatedEvent]] carrying the original JSON
  *
  * Registry failures are raised on the IO error channel; this service collects them with
  * `attemptNarrow` and re-raises the aggregate [[ValidationErrors]] so callers stay uniform.
  */
class ValidateService(registry: SchemaRegistry) {
  import ValidateService.EventSchemaRef

  def validate(rawBody: String): IO[ValidateResponse] =
    ValidateService.parse(rawBody) match {
      case Left(err) => IO.pure(ValidateResponse.Failure(ResponseError.from(err)))
      case Right(json) =>
        validateEvent(json).attemptNarrow[ValidationErrors].map {
          case Right(_)                     => ValidateResponse.Success
          case Left(ValidationErrors(errs)) => ValidateResponse.Failure(errs.flatMap(ResponseError.from))
        }
    }

  /** The shared core, reused by the /post service: validate `json` end-to-end, yielding the
    * [[ValidatedEvent]] or raising [[ValidationErrors]] with every collected [[ValidateError]].
    */
  def validateEvent(json: Json): IO[ValidatedEvent] =
    registry.validate(EventSchemaRef, json).attemptNarrow[ValidateError].flatMap {
      case Left(err) => IO.raiseError(ValidationErrors(NonEmptyList.one(err)))
      case Right(()) =>
        Event.fromJson(json) match {
          case Left(bug) =>
            IO.raiseError(new IllegalStateException(s"Event passed its schema but could not be extracted: $bug"))
          case Right(event) =>
            val payloads = event.data.body :: event.data.contexts.fold(List.empty[TypedData])(_.toList)
            payloads.parTraverse(td => registry.validate(td.schema, td.data).attemptNarrow[ValidateError]).flatMap { results =>
              NonEmptyList.fromList(results.collect { case Left(e) => e }) match {
                case Some(errors) => IO.raiseError(ValidationErrors(errors))
                case None =>
                  ValidatedEvent.of(event) match {
                    case Left(bug)        => IO.raiseError(new IllegalStateException(s"Validated event missing event_id: $bug"))
                    case Right(validated) => IO.pure(validated)
                  }
              }
            }
        }
    }
}

object ValidateService {
  private[validate] val EventSchemaRef: SchemaRef =
    SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))

  /** Parse the raw body as JSON. Shared by /validate and /post so both fail the same way on
    * non-JSON input. Pure and offline — the only check that does not go through the registry.
    */
  def parse(rawBody: String): Either[ValidateError, Json] =
    parser.parse(rawBody).left.map(err => MalformedJson(err.message))
}
