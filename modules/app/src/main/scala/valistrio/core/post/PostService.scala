package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.applicativeError._
import valistrio.core.ValistrioError.{SinkError, ValidationErrors}
import valistrio.core.http.ResponseError
import valistrio.core.validate.ValidateService

/** Orchestrates the /post flow: parse, validate (reusing the shared [[ValidateService]]),
  * then write the [[valistrio.core.domain.ValidatedEvent]] to the [[Sink]].
  *
  * `event_id` is the idempotency key for the written record; deduplicating repeated ids is
  * the sink's responsibility for 0.1.0 — PostService does not check for duplicates.
  */
class PostService(validateService: ValidateService, sink: Sink) {

  def post(rawBody: String): IO[PostResponse] =
    ValidateService.parse(rawBody) match {
      case Left(err) => IO.pure(PostResponse.Failure(ResponseError.from(err)))
      case Right(json) =>
        validateService.validateEvent(json).attemptNarrow[ValidationErrors].flatMap {
          case Left(ValidationErrors(errors)) =>
            IO.pure(PostResponse.Failure(errors.flatMap(ResponseError.from)))
          case Right(validated) =>
            sink.write(validated).attemptNarrow[SinkError].map {
              case Right(())   => PostResponse.Written
              case Left(error) => PostResponse.Failure(NonEmptyList.one(ResponseError.fromSink(error)))
            }
        }
    }
}
