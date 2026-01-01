package valistrio.core.http

import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import org.http4s.{Header, HttpApp, HttpRoutes, Request, Response}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{
  AutoSlash,
  Caching,
  ConcurrentRequests,
  DefaultHead,
  EntityLimiter,
  ErrorAction,
  ErrorHandling,
  Logger,
  MaxActiveRequests,
  ResponseTiming,
  Timeout
}
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
    val routes = Routes.health

    val addAuth: HttpRoutes[IO] => HttpRoutes[IO]        = identity // TODO
    val addAutoSlash: HttpRoutes[IO] => HttpRoutes[IO]   = AutoSlash(_)
    val addDefaultHead: HttpRoutes[IO] => HttpRoutes[IO] = DefaultHead(_)

    val addRoutingMiddlewareTo: HttpRoutes[IO] => HttpRoutes[IO] = addAuth.andThen(addAutoSlash).andThen(addDefaultHead)

    val service: HttpRoutes[IO] = addRoutingMiddlewareTo(routes)
    val baseApp: HttpApp[IO]    = service.orNotFound

    val addEntityLimit: HttpApp[IO] => HttpApp[IO] =
      EntityLimiter.httpApp(_, conf.maxBytes) // max(maxEventSize, maxRequestBodySize)
    val addTimeout: HttpApp[IO] => HttpApp[IO] = Timeout.httpApp[IO](conf.requestTimeout)(_)
    val addTiming: HttpApp[IO] => HttpApp[IO]  = ResponseTiming(_)

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

    val addCors: HttpApp[IO] => HttpApp[IO] = identity // TODO

    // make configurable
    val addLogging: HttpApp[IO] => HttpApp[IO] =
      Logger.httpApp(logHeaders = true, logBody = false) // logBody = true only for testing

    val addAppMiddlewareTo: HttpApp[IO] => HttpApp[IO] =
      addEntityLimit
        .andThen(addTimeout)
        .andThen(addTiming)
        .andThen(disableResponseCaching)
        .andThen(addErrorHandling)
        .andThen(addCors)
        .andThen(addLogging)

    addAppMiddlewareTo(baseApp)
  }

  private def errorHandler(t: Throwable, msg: => String): IO[Unit] =
    logger.error(t)(s"Error message: $msg \nCaused by:\n${t.getMessage}")
}
