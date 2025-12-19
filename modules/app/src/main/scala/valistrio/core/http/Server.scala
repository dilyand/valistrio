package valistrio.core.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import org.http4s.{HttpApp, HttpRoutes, Request, Response}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, Logger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config.ServerConfig

class Server(conf: ServerConfig) {
  implicit val logger: org.typelevel.log4cats.Logger[IO] = Slf4jLogger.getLogger[IO]

  private def errorHandler(t: Throwable, msg: => String): IO[Unit] =
    logger.error(t)(s"Error Message: $msg \nStack Trace:\n${t.getStackTrace.mkString("Array(", ", ", ")")}")

  def run: IO[Unit] = {
    val service: HttpRoutes[IO] = Routes.health
    val base: HttpApp[IO]       = service.orNotFound

    val addErrorHandling: HttpApp[IO] => HttpApp[IO] =
      app =>
        ErrorHandling
          .Recover
          .total(
            ErrorAction.log(
              app,
              messageFailureLogAction = errorHandler,
              serviceErrorLogAction = errorHandler
            )
          )

    val addLogging: HttpApp[IO] => HttpApp[IO] =
      app => Logger.httpApp(logHeaders = true, logBody = false)(app) // TODO: revisit logBody

    val addMiddlewareTo: HttpApp[IO] => HttpApp[IO] = addErrorHandling.andThen(addLogging)

    val httpApp = addMiddlewareTo(base)

    for {
      host <- IO.fromOption(Host.fromString(conf.host))(new RuntimeException(s"Invalid host: ${conf.host}"))
      port <- IO.fromOption(Port.fromInt(conf.port))(new RuntimeException(s"Invalid port: ${conf.port}"))
      _    <- EmberServerBuilder.default[IO].withHost(host).withPort(port).withHttpApp(httpApp).build.useForever
    } yield ()
  }
}
