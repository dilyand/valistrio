package valistrio.core.resources

import cats.effect.{IO, Resource}
import fs2.kafka._
import org.apache.kafka.clients.admin.{AdminClient, AdminClientConfig}
import valistrio.core.Config.KafkaConfig

import java.util.Properties
import java.util.concurrent.TimeUnit
import scala.concurrent.duration._

/** Provides the shared Kafka producer both the events and DLQ [[KafkaSink]]s write through. */
object Kafka {

  private val ConnectivityCheckTimeout = 5.seconds

  /** Creates the shared producer. On acquisition, probes broker connectivity so the app fails
    * fast at startup if Kafka is unreachable rather than only on the first `/post`. The probe
    * checks cluster reachability only (not that the configured topics exist) — topics are commonly
    * provisioned out-of-band, and brokers often disable auto-topic-creation.
    */
  def producer(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] =
    Resource.eval(checkConnectivity(config)).flatMap(_ => producerResource(config))

  private def checkConnectivity(config: KafkaConfig): IO[Unit] =
    adminClient(config)
      .use(admin => IO.blocking(admin.describeCluster().nodes().get(ConnectivityCheckTimeout.toMillis, TimeUnit.MILLISECONDS)))
      .timeout(ConnectivityCheckTimeout)
      .void

  private def adminClient(config: KafkaConfig): Resource[IO, AdminClient] =
    Resource.fromAutoCloseable(IO.blocking {
      val props = new Properties()
      props.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, config.bootstrapServers)
      props.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, ConnectivityCheckTimeout.toMillis.toString)
      AdminClient.create(props)
    })

  private def producerResource(config: KafkaConfig): Resource[IO, KafkaProducer[IO, String, String]] = {
    val settings = ProducerSettings(Serializer[IO, String], Serializer[IO, String])
      .withBootstrapServers(config.bootstrapServers)
      .withAcks(Acks.All)

    KafkaProducer.resource(settings)
  }
}
