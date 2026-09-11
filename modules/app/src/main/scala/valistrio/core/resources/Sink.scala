package valistrio.core.resources

import cats.effect.IO
import valistrio.core.domain.Writable

/** Algebra for writing a [[Writable]] to a downstream transport. Parameterized on the payload so
  * a sink accepts only one kind — an events sink is a `Sink[ValidatedEvent]`, a DLQ sink a
  * `Sink[FailedEvent]` — and writing the wrong shape to a topic is a compile error.
  *
  * A write failure is raised as a [[valistrio.core.ValistrioError.SinkError]] on the IO error
  * channel; the caller recovers it (e.g. to route an owned failure to the DLQ, or to fail /post).
  */
trait Sink[A <: Writable] {
  def write(a: A): IO[Unit]
}
