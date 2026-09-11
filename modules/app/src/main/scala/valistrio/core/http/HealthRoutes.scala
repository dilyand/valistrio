package valistrio.core.http

import cats.effect.IO
import org.http4s.HttpRoutes
import org.http4s.dsl.io._

/** The GET /health route: a dependency-free liveness check. */
object HealthRoutes {

  def routes: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }
}
