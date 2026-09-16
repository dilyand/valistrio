package valistrio.core.pipeline

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.applicativeError._
import cats.syntax.parallel._
import io.circe.{Json, parser}
import valistrio.core.ValistrioError.{ValidateError, ValidationErrors}
import valistrio.core.ValistrioError.ValidateError._
import valistrio.core.domain.{Event, ResponseError, SchemaRef, SchemaVersion, TypedData, ValidateResponse}
import valistrio.core.domain.Writable.ValidatedEvent
import valistrio.core.resources.schemas.SchemaRegistry

/** The validation pipeline, shared by the /validate and /post routes. The event schema is the sole
  * structural authority; validation is a multi-pass parse:
  *
  *  1. [[Validation.parse]] turns the raw body into JSON — [[MalformedJson]] on failure (the only
  *     offline check)
  *  2. validate the JSON against the event schema — structural, format and ref-shape failures all
  *     surface as [[ValidationFailed]]
  *  3. extract the navigable [[Event]] — total after (2); a failure is an internal bug
  *  4. validate `body` and each context against their own schemas, collecting all errors
  *  5. on success, produce a [[ValidatedEvent]] carrying the original JSON
  *
  * Registry failures are raised on the IO error channel; [[validateEvent]] collects them with
  * `attemptNarrow` and re-raises the aggregate [[ValidationErrors]] so callers stay uniform.
  */
class Validation(registry: SchemaRegistry) {
  import Validation.EventSchemaRef

  /** The /validate outcome: run the pipeline and report whether the event conforms. */
  def validate(rawBody: String): IO[ValidateResponse] =
    Validation.parse(rawBody) match {
      case Left(err) => IO.pure(ValidateResponse.Failure(ResponseError.from(err)))
      case Right(json) =>
        validateEvent(json).attemptNarrow[ValidationErrors].map {
          case Right(_)                     => ValidateResponse.Success
          case Left(ValidationErrors(errs)) => ValidateResponse.Failure(errs.flatMap(ResponseError.from))
        }
    }

  /** The shared core, reused by the /post pipeline: validate `json` end-to-end, yielding the
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
            // Prefix each payload's error paths with its location in the event, so a body error and
            // same-schema context errors are distinguishable (e.g. `$.data.contexts[1].data.user_id`).
            val payloads: List[(String, TypedData)] =
              ("$.data.body.data" -> event.data.body) ::
                event.data.contexts.fold(List.empty[(String, TypedData)]) {
                  _.toList.zipWithIndex.map { case (td, i) => s"$$.data.contexts[$i].data" -> td }
                }
            payloads.parTraverse { case (location, td) =>
              registry.validate(td.schema, td.data).attemptNarrow[ValidateError].map {
                case Left(ValidationFailed(errs)) =>
                  Left(ValidationFailed(errs.map(e => e.copy(path = location + e.path.stripPrefix("$")))))
                case other => other
              }
            }.flatMap { results =>
              NonEmptyList.fromList(results.collect { case Left(e) => e }) match {
                case Some(errors) => IO.raiseError(ValidationErrors(errors))
                case None =>
                  // Validation is the sole producer of ValidatedEvent: extract the id here, once
                  // every payload has validated. A missing id means the event passed a schema that
                  // requires it — a bug, not client input.
                  event.data.meta.hcursor.get[String]("event_id") match {
                    case Left(bug) => IO.raiseError(new IllegalStateException(s"Validated event missing event_id: ${bug.getMessage}"))
                    case Right(id) => IO.pure(ValidatedEvent(event.json, id))
                  }
              }
            }
        }
    }
}

object Validation {
  private[pipeline] val EventSchemaRef: SchemaRef =
    SchemaRef("io.github.dilyand.valistrio", "event", SchemaVersion(1, 0, 0))

  /** Parse the raw body as JSON. Shared by /validate and /post so both fail the same way on
    * non-JSON input. Pure and offline — the only check that does not go through the registry.
    */
  def parse(rawBody: String): Either[ValidateError, Json] =
    parser.parse(rawBody).left.map(err => MalformedJson(err.message))
}
