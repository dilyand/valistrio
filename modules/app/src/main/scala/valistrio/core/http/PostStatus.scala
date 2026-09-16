package valistrio.core.http

import cats.data.NonEmptyList
import org.http4s.Status
import valistrio.core.domain.{PostResponse, ResponseError}

/** The HTTP status for a /post outcome, shared by the JSON `/post` route and the status-only
  * inbound adapters. An owned event — written to the events topic or salvaged to the DLQ — is 200;
  * a transient infrastructure failure maps to the 5xx the producer should retry.
  *
  * A `Failed` outcome carries only transient (Retry) errors, since owned failures are salvaged and
  * return 200, so the `else` is unreachable and signals a classification bug.
  */
object PostStatus {

  def of(resp: PostResponse): Status = resp match {
    case PostResponse.Written        => Status.Ok
    case PostResponse.Dlqd(_)        => Status.Ok
    case PostResponse.Failed(errors) => forFailed(errors)
  }

  private def forFailed(errors: NonEmptyList[ResponseError]): Status = {
    val types = errors.toList.map(_.`type`).toSet
    if (types.contains("schema_registry_unavailable") || types.contains("sink_unavailable")) Status.ServiceUnavailable
    else if (types.contains("schema_registry_timeout") || types.contains("sink_timeout")) Status.GatewayTimeout
    else if (types.contains("sink_write_failed")) Status.BadGateway
    else Status.InternalServerError
  }
}
