package valistrio.core.post

import cats.effect.IO

/** Algebra for writing a [[FailedEvent]] to the dead-letter transport.
  *
  * As with [[Sink]], a write failure is raised as a [[valistrio.core.ValistrioError.SinkError]]
  * on the IO error channel; the caller recovers it (a DLQ write that also fails means the event
  * could not be owned, so the request is retried).
  */
trait DlqSink {
  def write(failed: FailedEvent): IO[Unit]
}
