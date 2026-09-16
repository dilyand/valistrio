package valistrio.core.adapters

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import org.http4s._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.specs2.mutable.Specification
import org.typelevel.ci._

class AdapterCorsSpec extends Specification {

  private val inner: HttpRoutes[IO] =
    HttpRoutes.of[IO] { case POST -> Root / "v1" / "track" => Ok("ok") }

  private val app: HttpApp[IO] = AdapterCors(inner).orNotFound

  private def allowOrigin(resp: Response[IO]): Option[String] =
    resp.headers.get(ci"Access-Control-Allow-Origin").map(_.head.value)

  private val origin = Header.Raw(ci"Origin", "https://demo.example")

  "AdapterCors" should {

    "answer a preflight OPTIONS with an allow-origin header" in {
      val req = Request[IO](Method.OPTIONS, uri"/v1/track")
        .putHeaders(origin, Header.Raw(ci"Access-Control-Request-Method", "POST"))
      val resp = app.run(req).unsafeRunSync()
      resp.status must beEqualTo(Status.Ok)
      allowOrigin(resp) must beSome("*")
    }

    "decorate the actual POST response with an allow-origin header" in {
      val req  = Request[IO](Method.POST, uri"/v1/track").putHeaders(origin).withEntity("{}")
      val resp = app.run(req).unsafeRunSync()
      resp.status must beEqualTo(Status.Ok)
      allowOrigin(resp) must beSome("*")
    }
  }
}
