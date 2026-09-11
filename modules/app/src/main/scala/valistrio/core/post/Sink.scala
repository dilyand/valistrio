package valistrio.core.post

import cats.effect.IO
import valistrio.core.domain.ValidatedEvent

/** Algebra for writing a validated event to a downstream transport.
  *
  * A write failure is raised as a [[valistrio.core.ValistrioError.SinkError]] on the IO
  * error channel; the caller recovers it (e.g. to route the event to the DLQ).
  */
trait Sink {
  def write(event: ValidatedEvent): IO[Unit]
}
