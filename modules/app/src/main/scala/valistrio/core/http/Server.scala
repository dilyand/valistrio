package valistrio.core.http

import cats.data.OptionT
import cats.effect.IO
import com.comcast.ip4s.{Host, Port}
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.middleware.{ErrorAction, ErrorHandling, Logger}
import org.typelevel.log4cats.slf4j.Slf4jLogger

class Server {
  implicit val logger: org.typelevel.log4cats.Logger[IO] = Slf4jLogger.getLogger[IO]

  private def errorHandler(t: Throwable, msg: => String)(implicit
                                                         logger: org.typelevel.log4cats.Logger[IO]
  ): OptionT[IO, Unit] =
    OptionT.liftF(
      logger.error(t)(s"Error Message: $msg \nStack Trace:\n${t.getStackTrace}")
    )

  def run = {
    val routes = Routes.health

    val httpApp = ErrorHandling
      .Recover
      .total(
        ErrorAction.log(
          routes,
          messageFailureLogAction = errorHandler,
          serviceErrorLogAction = errorHandler
        )
      )
      .orNotFound

    // TODO - revisit logBody
    val finalHttpApp = Logger.httpApp(logHeaders = true, logBody = false)(httpApp)

    val server = for {
      host <- OptionT.fromOption[IO](Host.fromString("0.0.0.0"))
      port <- OptionT.fromOption[IO](Port.fromInt(8080))
      server <-
        OptionT.liftF(
          IO.pure(EmberServerBuilder.default[IO].withHost(host).withPort(port).withHttpApp(finalHttpApp).build)
        )
    } yield server

    server.value.flatMap {
      case Some(server) => server.useForever
      case _ =>
        IO.raiseError[Unit](
          new RuntimeException(
            s"Invalid host or port configuration. Host: 0.0.0.0, port: 8080"
          )
        )
    }
  }
}
