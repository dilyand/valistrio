package valistrio.core.adapters

import cats.effect.IO
import org.http4s.{HttpRoutes, Method}
import org.http4s.server.middleware.CORS

/** CORS for inbound browser-SDK adapters. Their data-plane POST is cross-origin with a JSON content
  * type and custom headers, so the browser sends a preflight `OPTIONS` first. The RudderStack SDK
  * sends no credentials by default, so `Access-Control-Allow-Origin: *` — and a `*` allow-headers —
  * is permissible. The middleware answers the preflight and decorates the actual response; the
  * wrapped routes never see the `OPTIONS`.
  */
object AdapterCors {

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    CORS.policy.withAllowOriginAll
      .withAllowMethodsIn(Set(Method.POST))
      .withAllowHeadersAll
      .apply(routes)
}
