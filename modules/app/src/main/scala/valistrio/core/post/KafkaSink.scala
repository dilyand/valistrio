package valistrio.core.post

import cats.effect.{IO, Resource}
import fs2.kafka._
import io.circe.syntax._
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import org.apache.kafka.common.errors.{TimeoutException => KafkaTimeoutException}
import valistrio.core.Config.KafkaConfig
import valistrio.core.ValistrioError.SinkError
import valistrio.core.ValistrioError.SinkError._
import valistrio.core.domain.Event

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

object KafkaSink {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates a [[Sink]][IO] backed by an `fs2-kafka` producer, writing to `config.topic`.
    *
    * On resource acquisition, probes the broker for connectivity so the application
    * fails fast at startup if Kafka is unreachable, rather than only discovering it on
    * the first `/post` request. The probe checks cluster reachability only (not that
    * `config.topic` already exists) — topics are commonly provisioned out-of-band by
    * admin tooling, possibly after the app's first deploy, and brokers are frequently
    * configured with auto-topic-creation disabled.
    */
  def resource(config: KafkaConfig): Resource[IO, Sink[IO]] =
    Resource.eval(checkConnectivity(config)).flatMap { _ =>
      producerResource(config).map(new LiveKafkaSink(_, config.topic))
    }

  // ---- Private implementation ----

  private def checkConnectivity(config: KafkaConfig): IO[Unit] =
    IO.blocking {
      val props = new Properties()
      props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ConnectivityCheckTimeout.toMillis.toString)

      val admin = AdminClient.create(props)
      try admin.describeCluster().nodes().get(ConnectivityCheckTimeout.toMillis, TimeUnit.MILLISECONDS)
      finally admin.close()
    }.timeout(ConnectivityCheckTimeout).void

  private def producerResource(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] = {
    val settings = ProducerSettings(Serializer[IO, String], Serializer[IO, String])
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)

    KafkaProducer.resource(settings)
  }

  private class LiveKafkaSink(producer: KafkaProducer[IO, String, String], topic: String) extends Sink[IO] {

    def write(envelope: Event): IO[Either[SinkError, Unit]] = {
      val record = ProducerRecord(topic, envelope.data.meta.eventId, envelope.asJson.noSpaces)
      producer
        .produceOne_(record)
        .flatten
        .attempt
        .map {
          case Right(_)                       => Right(())
          case Left(_: KafkaTimeoutException) => Left(Timeout)
          case Left(e)                        => Left(WriteFailed(e.getMessage))
        }
    }
  }
}
