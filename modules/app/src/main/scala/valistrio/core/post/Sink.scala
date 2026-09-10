package valistrio.core.post

import cats.effect.IO
import valistrio.core.ValistrioError.SinkError
import valistrio.core.domain.Event

/** Algebra for writing a validated event to a downstream transport. */
trait Sink {

  /** Write `event`. A [[SinkError]] on the Left means the write could not be completed;
    * the caller routes the event to the DLQ (see CLAUDE.md for the DLQ format).
    */
  def write(event: Event): IO[Either[SinkError, Unit]]
}
