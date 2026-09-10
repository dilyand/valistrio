package valistrio.core.post

import cats.effect.{IO, Ref}
import valistrio.core.ValistrioError.SinkError
import valistrio.core.domain.Event

/** In-memory [[Sink]][IO] for testing code that writes through the algebra
  * (e.g. the /post service) without a real Kafka broker.
  */
final class StubSink private (ref: Ref[IO, Vector[Event]], result: Either[SinkError, Unit])
    extends Sink {

  def write(envelope: Event): IO[Either[SinkError, Unit]] =
    ref.update(_ :+ envelope).as(result)

  def written: IO[Vector[Event]] = ref.get
}

object StubSink {

  /** A stub that records every write and always succeeds. */
  def succeeding: IO[StubSink] =
    Ref.of[IO, Vector[Event]](Vector.empty).map(new StubSink(_, Right(())))

  /** A stub that records every write but reports `error` for each one. */
  def failingWith(error: SinkError): IO[StubSink] =
    Ref.of[IO, Vector[Event]](Vector.empty).map(new StubSink(_, Left(error)))
}
