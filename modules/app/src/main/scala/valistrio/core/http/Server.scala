package valistrio.core.http

import cats.effect.IO
import cats.implicits.toSemigroupKOps
import com.comcast.ip4s.{Host, Port}
import org.http4s.{HttpApp, HttpRoutes}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{AutoSlash, Caching, DefaultHead, EntityLimiter, ErrorAction, ErrorHandling, Logger, ResponseTiming, Timeout}
import org.typelevel.log4cats.{Logger => Log4CatsLogger}
import valistrio.core.Config.ServerConfig
import valistrio.core.post.{PostService, Sink}
import valistrio.core.validate.{SchemaRegistry, ValidateService}

class Server(conf: ServerConfig, schemaRegistry: SchemaRegistry, sink: Sink, logger: Log4CatsLogger[IO]) {

  def run: IO[Unit] =
    for {
      host <- IO.fromOption(Host.fromString(conf.host))(new RuntimeException(s"Invalid host: ${conf.host}"))
      port <- IO.fromOption(Port.fromInt(conf.port))(new RuntimeException(s"Invalid port: ${conf.port}"))
      _    <- EmberServerBuilder.default[IO].withHost(host).withPort(port).withHttpApp(mkApp).build.useForever
    } yield ()

  private def mkApp: HttpApp[IO] = {
    val validateService = new ValidateService(schemaRegistry)
    val postService     = new PostService(validateService, sink)
    val routes          = Routes.health <+> Routes.validate(validateService) <+> Routes.post(postService)

    val addAutoSlash: HttpRoutes[IO] => HttpRoutes[IO]   = AutoSlash(_)
    val addDefaultHead: HttpRoutes[IO] => HttpRoutes[IO] = DefaultHead(_)

    val addRoutingMiddlewareTo: HttpRoutes[IO] => HttpRoutes[IO] = addAutoSlash.andThen(addDefaultHead)

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
          val shouldDisable = p == "/health" || p == "/validate" || p == "/post"

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
