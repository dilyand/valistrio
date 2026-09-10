package valistrio.core.post

import cats.data.NonEmptyList
import cats.effect.IO
import valistrio.core.validate.{SchemaRegistry, ValidateResponse, ValidateResponseError, ValidateService}

/** Orchestrates the full /post request flow: parse, decode, validate (reusing
  * [[ValidateService]]), then write the validated envelope to the [[Sink]].
  *
  * `meta.event_id` is the idempotency key for the written record (see
  * [[valistrio.core.post.KafkaSink]], which uses it as the Kafka record key).
  * Deduplicating repeated `event_id`s is the sink's responsibility for 0.1.0 —
  * PostService itself does not check for or reject duplicates.
  */
class PostService(registry: SchemaRegistry, sink: Sink) {

  private val validateService = new ValidateService(registry)

  def post(rawBody: String): IO[PostResponse] =
    ValidateService.parseAndDecode(rawBody) match {
      case Left(err) =>
        IO.pure(PostResponse.Failure(ValidateResponseError.from(err).map(PostResponseError.fromValidate)))

      case Right((json, envelope)) =>
        validateService.validateAll(json, envelope).flatMap {
          case ValidateResponse.Failure(errors) =>
            IO.pure(PostResponse.Failure(errors.map(PostResponseError.fromValidate)))

          case ValidateResponse.Success =>
            sink.write(envelope).map {
              case Right(())   => PostResponse.Written
              case Left(error) => PostResponse.Failure(NonEmptyList.one(PostResponseError.fromSink(error)))
            }
        }
    }
}
