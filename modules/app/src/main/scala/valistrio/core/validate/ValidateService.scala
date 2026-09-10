package valistrio.core.validate

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.parallel._
import io.circe.{Json, parser}
import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{Event, SchemaRef, SchemaVersion, TypedData, ValidatedEvent}

/** Orchestrates validation for /validate and /post.
  *
  * Multi-pass parse (the event schema is the sole structural authority):
  *  1. parse the body as JSON — [[MalformedJson]] on failure (the only offline check)
  *  2. validate the JSON against the event schema — structural, format and ref-shape
  *     failures all surface as [[ValidationFailed]]
  *  3. extract the navigable [[Event]] — total after (2); a failure is an internal bug
  *  4. validate `body` and each context against their own schemas, collecting all errors
  *  5. on success, assemble a [[ValidatedEvent]] carrying the original JSON
  */
class ValidateService(registry: SchemaRegistry) {
  import ValidateService.EventSchemaRef

  def validate(rawBody: String): IO[ValidateResponse] =
    ValidateService.parse(rawBody) match {
      case Left(err)   => IO.pure(ValidateResponse.Failure(ValidateResponseError.from(err)))
      case Right(json) => validateEvent(json).map(toResponse)
    }

  /** The shared core, reused by the /post service: validate `json` end-to-end, yielding the
    * [[ValidatedEvent]] on success or every collected [[ValidateError]] on failure.
    */
  def validateEvent(json: Json): IO[Either[NonEmptyList[ValidateError], ValidatedEvent]] =
    registry.validate(EventSchemaRef, json).flatMap {
      case Left(err) => IO.pure(Left(NonEmptyList.one(err)))
      case Right(()) =>
        Event.fromJson(json) match {
          case Left(bug) =>
            IO.raiseError(new IllegalStateException(s"Event passed its schema but could not be extracted: $bug"))
          case Right(event) =>
            val payloads = event.data.body :: event.data.contexts.fold(List.empty[TypedData])(_.toList)
            payloads.parTraverse(td => registry.validate(td.schema, td.data)).flatMap { results =>
              NonEmptyList.fromList(results.collect { case Left(e) => e }) match {
                case Some(errors) => IO.pure(Left(errors))
                case None =>
                  ValidatedEvent.of(event) match {
                    case Left(bug)        => IO.raiseError(new IllegalStateException(s"Validated event missing event_id: $bug"))
                    case Right(validated) => IO.pure(Right(validated))
                  }
              }
            }
        }
    }

  private def toResponse(result: Either[NonEmptyList[ValidateError], ValidatedEvent]): ValidateResponse =
    result match {
      case Right(_)     => ValidateResponse.Success
      case Left(errors) => ValidateResponse.Failure(errors.flatMap(ValidateResponseError.from))
    }
}

object ValidateService {
  private[validate] val EventSchemaRef: SchemaRef =
    SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))

  /** Parse the raw body as JSON. Shared by /validate and /post so both fail the same way on
    * non-JSON input.
    */
  def parse(rawBody: String): Either[ValidateError, Json] =
    parser.parse(rawBody).left.map(err => MalformedJson(err.message))
}
