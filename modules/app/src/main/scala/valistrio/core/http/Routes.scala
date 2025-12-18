package valistrio.core.http

import cats.effect.IO
import org.http4s.dsl.Http4sDsl
import org.http4s.{HttpRoutes, Response}

object Routes {
  def health: HttpRoutes[IO] = {
    val dsl = new Http4sDsl[IO] {}
    import dsl._

    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }
  }
}
