package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.IO
import cats.syntax.applicativeError._
import valistrio.core.ValistrioError.{SinkError, ValidateError}
import valistrio.core.validate.{ValidateResponseError, ValidateService}

/** Orchestrates the /post flow: parse, validate (reusing the shared [[ValidateService]]),
  * then write the [[valistrio.core.domain.ValidatedEvent]] to the [[Sink]].
  *
  * `event_id` is the idempotency key for the written record; deduplicating repeated ids is
  * the sink's responsibility for 0.1.0 — PostService does not check for duplicates.
  */
class PostService(validateService: ValidateService, sink: Sink) {

  def post(rawBody: String): IO[PostResponse] =
    ValidateService.parse(rawBody) match {
      case Left(err) => IO.pure(PostResponse.Failure(toPostErrors(NonEmptyList.one(err))))
      case Right(json) =>
        validateService.validateEvent(json).flatMap {
          case Left(errors) => IO.pure(PostResponse.Failure(toPostErrors(errors)))
          case Right(validated) =>
            sink.write(validated).attemptNarrow[SinkError].map {
              case Right(())   => PostResponse.Written
              case Left(error) => PostResponse.Failure(NonEmptyList.one(PostResponseError.fromSink(error)))
            }
        }
    }

  private def toPostErrors(errors: NonEmptyList[ValidateError]): NonEmptyList[PostResponseError] =
    errors.flatMap(e => ValidateResponseError.from(e).map(PostResponseError.fromValidate))
}
