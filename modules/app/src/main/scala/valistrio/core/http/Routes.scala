package valistrio.core.http

import cats.effect.IO
import org.http4s.dsl.io._
import org.http4s.{HttpRoutes, Response}

object Routes {
  def health: HttpRoutes[IO] = {
    HttpRoutes.of[IO] { case GET -> Root / "health" =>
      Ok("ok")
    }
  }
}
