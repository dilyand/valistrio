package valistrio.core

import valistrio.core.ValistrioError.ValidateError
import valistrio.core.ValistrioError.ValidateError._

/** The semantic (non-HTTP) disposition of a failure on the /post path.
  *
  *  - [[Disposition.Owned]]: Valistrio accepts the event but it failed validation, so it is
  *    salvaged to the DLQ and the request still succeeds (200). Nothing the producer can retry.
  *  - [[Disposition.Retry]]: a transient infrastructure failure — the event could not be owned,
  *    so the request fails (5xx) and the producer is expected to retry the whole /post.
  *
  * `Retry` dominates: any `Retry` error makes the whole request `Retry`. Distinct from the
  * informational `recoverable` wire flag.
  */
sealed trait Disposition
object Disposition {
  case object Owned extends Disposition
  case object Retry extends Disposition

  def of(e: ValidateError): Disposition = e match {
    case _: MalformedJson | _: SchemaNotFound | _: ValidationFailed => Owned
    case _: SchemaRegistryUnavailable | SchemaRegistryTimeout       => Retry
  }
}
