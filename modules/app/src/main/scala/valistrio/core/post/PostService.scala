package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.applicativeError._
import io.circe.Json
import valistrio.core.ValistrioError.{SinkError, ValidationErrors}
import valistrio.core.domain.{Disposition, FailedEvent, ResponseError, ValidatedEvent}
import valistrio.core.resources.Sink
import valistrio.core.validate.ValidateService

/** Orchestrates the /post flow: parse, validate (reusing the shared [[ValidateService]]), then
  * either write the [[ValidatedEvent]] to the events [[Sink]] or, when the event failed validation
  * in a way Valistrio owns, salvage it to the DLQ sink.
  *
  * "Once we get it, we own it": an owned failure still returns 200 (`written` = `dlq`). Only a
  * transient infrastructure failure — the registry unreachable, the events write failing, or the
  * DLQ write itself failing — yields a 5xx the producer is expected to retry.
  *
  * `event_id` is the idempotency key for the written record; deduplicating repeated ids is the
  * sink's responsibility for 0.1.0 — PostService does not check for duplicates.
  */
class PostService(
  validateService: ValidateService,
  eventSink: Sink[ValidatedEvent],
  dlqSink: Sink[FailedEvent],
  maxBytes: Long
) {

  def post(rawBody: String): IO[PostResponse] =
    ValidateService.parse(rawBody) match {
      case Left(err) =>
        // Malformed JSON is owned: salvage the raw body (as a JSON string) to the DLQ.
        toDlq(Json.fromString(rawBody), ResponseError.from(err))
      case Right(json) =>
        validateService.validateEvent(json).attemptNarrow[ValidationErrors].flatMap {
          case Left(ValidationErrors(errors)) =>
            if (errors.exists(Disposition.of(_) == Disposition.Retry))
              IO.pure(PostResponse.Failed(errors.flatMap(ResponseError.from)))
            else
              toDlq(json, errors.flatMap(ResponseError.from))
          case Right(validated) =>
            eventSink.write(validated).attemptNarrow[SinkError].map {
              case Right(())   => PostResponse.Written
              case Left(error) => PostResponse.Failed(NonEmptyList.one(ResponseError.fromSink(error)))
            }
        }
    }

  /** Package the failed event and write it to the DLQ. A DLQ write that also fails means the event
    * could not be owned, so the request is demoted to a 5xx carrying just the sink error — the
    * producer retries the whole /post rather than inspecting the response.
    */
  private def toDlq(original: Json, errors: NonEmptyList[ResponseError]): IO[PostResponse] =
    IO.realTimeInstant.flatMap { now =>
      val failed = FailedEvent.of(original, errors, now, maxBytes)
      dlqSink.write(failed).attemptNarrow[SinkError].map {
        case Right(())   => PostResponse.Dlqd(errors)
        case Left(error) => PostResponse.Failed(NonEmptyList.one(ResponseError.fromSink(error)))
      }
    }
}
