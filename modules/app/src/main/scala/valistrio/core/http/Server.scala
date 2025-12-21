package valistrio.core.http

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import org.http4s.{Header, HttpApp, HttpRoutes, Request, Response}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{Caching, ErrorAction, ErrorHandling, Logger}
import org.typelevel.log4cats.slf4j.Slf4jLogger
import valistrio.core.Config.ServerConfig

class Server(conf: ServerConfig) {
  implicit val logger: org.typelevel.log4cats.Logger[IO] = Slf4jLogger.getLogger[IO]

  def run: IO[Unit] =
    for {
      host <- IO.fromOption(Host.fromString(conf.host))(new RuntimeException(s"Invalid host: ${conf.host}"))
      port <- IO.fromOption(Port.fromInt(conf.port))(new RuntimeException(s"Invalid port: ${conf.port}"))
      _    <- EmberServerBuilder.default[IO].withHost(host).withPort(port).withHttpApp(mkApp).build.useForever
    } yield ()

  private def mkApp: HttpApp[IO] = {
    val service: HttpRoutes[IO] = Routes.health
    val base: HttpApp[IO]       = service.orNotFound

    val disableResponseCaching: HttpApp[IO] => HttpApp[IO] = { app =>
      HttpApp[IO] { req =>
        app(req).flatMap { resp =>
          val p             = req.uri.path.renderString
          val shouldDisable = p == "/health"

          if (shouldDisable) Caching.`no-store-response`[IO](resp)
          else IO.pure(resp)
        }
      }
    }

    val addErrorHandling: HttpApp[IO] => HttpApp[IO] = { app =>
      ErrorHandling
        .Recover
        .total(
          ErrorAction.log(
            app,
            messageFailureLogAction = errorHandler,
            serviceErrorLogAction = errorHandler
          )
        )
    }

    val addLogging: HttpApp[IO] => HttpApp[IO] =
      Logger.httpApp(logHeaders = true, logBody = false) // TODO: revisit logBody

    val addMiddlewareTo: HttpApp[IO] => HttpApp[IO] =
      disableResponseCaching.andThen(addErrorHandling).andThen(addLogging)

    addMiddlewareTo(base)
  }

  private def errorHandler(t: Throwable, msg: => String): IO[Unit] =
    logger.error(t)(s"Error Message: $msg \nStack Trace:\n${t.getStackTrace.mkString("Array(", ", ", ")")}")
}
