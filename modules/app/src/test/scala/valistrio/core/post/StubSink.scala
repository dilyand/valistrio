package valistrio.core.post

import cats.effect.{IO, Ref}
import valistrio.core.ValistrioError.SinkError
import valistrio.core.domain.ValidatedEvent

/** In-memory [[Sink]] for testing code that writes through the algebra (e.g. the /post
  * service) without a real Kafka broker.
  */
final class StubSink private (ref: Ref[IO, Vector[ValidatedEvent]], result: Either[SinkError, Unit])
    extends Sink {

  def write(event: ValidatedEvent): IO[Either[SinkError, Unit]] =
    ref.update(_ :+ event).as(result)

  def written: IO[Vector[ValidatedEvent]] = ref.get
}

object StubSink {

  /** A stub that records every write and always succeeds. */
  def succeeding: IO[StubSink] =
    Ref.of[IO, Vector[ValidatedEvent]](Vector.empty).map(new StubSink(_, Right(())))

  /** A stub that records every write but reports `error` for each one. */
  def failingWith(error: SinkError): IO[StubSink] =
    Ref.of[IO, Vector[ValidatedEvent]](Vector.empty).map(new StubSink(_, Left(error)))
}
