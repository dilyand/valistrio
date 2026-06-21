package valistrio.core.post

import valistrio.core.ValistrioError.SinkError
import valistrio.core.domain.TransportEnvelope

/** Algebra for writing a validated envelope to a downstream sink (Kafka, HTTP, in-memory for tests).
  *
  * The algebra hides all transport details, allowing the /post service to remain
  * independent of the particular sink implementation.
  */
trait Sink[F[_]] {

  /** Write `envelope` to the sink.
    *
    * Returns:
    *  - [[scala.Right]] on success
    *  - [[scala.Left]] with [[valistrio.core.ValistrioError.SinkError]] if the write
    *    could not be completed; the caller is responsible for routing the envelope
    *    to the DLQ (see CLAUDE.md for the DLQ envelope format)
    */
  def write(envelope: TransportEnvelope): F[Either[SinkError, Unit]]
}
