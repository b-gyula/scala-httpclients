
import com.github.plokhotnyuk.jsoniter_scala.core.readFromStream
import com.linecorp.armeria.client.{ClientFactoryBuilder, ClientOptions, WebClient}
import com.linecorp.armeria.common.{HttpData, HttpMethod, HttpObject, HttpRequest, RequestHeaders, SessionProtocol}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.capabilities.zio.ZioStreams
import zio.{Chunk, Exit, Runtime, Task, Unsafe, ZIO}
import sttp.client3._

import java.nio.charset.StandardCharsets.UTF_8
import org.reactivestreams.{Subscriber, Subscription}
import zio.stream.ZSink

import java.io.{FileOutputStream, InputStream}
import scala.concurrent.Await
import scala.jdk.FunctionConverters._
import scala.jdk.FutureConverters._
import scala.concurrent.duration.{Duration, DurationInt}
import scala.io.Source
import scala.reflect.internal.ClassfileConstants.instanceof
import scala.util.Using
import Endpoint._

object ArmeriaStreamTest extends StreamTest {

	val ccCoinList = uri"https://min-api.cryptocompare.com/data/all/coinlist"

	val req = HttpRequest.of(RequestHeaders.builder()
		.scheme(ccCoinList.scheme.get)
		.authority(ccCoinList.host.get)
		.method(HttpMethod.GET)
		.path(ccCoinList.pathSegments.toString)
		.build())


	val cliOpts = ClientOptions.builder().maxResponseLength(40*1024*1024)
		.build()

	def mapper(o: HttpObject): HttpData = o match {
		case d: HttpData => d
		case _ => HttpData.empty
	}

	def main(args: Array[String]): Unit =
		toInputStream
		//runZIO

	def toInputStream = { // Fully blocking
		val r = WebClient.builder().options(cliOpts).build()
			.execute(req)
		val is = r.toInputStream(mapper)
		println(readFromStream(is).Data.size)
	}

	def runZIO = {
		implicit val rts = zio.Runtime.default
		val r = unsafeRunSync (getZIO)
		println(r.Data.size)
	}

	def getZIO = {
		import zio._
		import zio.interop.reactivestreams._

		def parse(is: InputStream) = ZIO.attempt {
			readFromStream(is)
		}

		val resp = WebClient.builder().options(cliOpts).build()
			.execute(req)

		val drain = ZIO.fromCompletableFuture( resp.whenComplete )

		ZIO.scoped {
			for {
				is <- resp.toZIOStream(2)
					.mapConcatChunk {
						case d: HttpData =>
							//println(s"INFO: onNext:" + d.length)
							Chunk.fromArray(d.array)
						case x => println(s"INFO: onNext:" + x)
							Chunk.empty
					}
					.tapSink(ZSink.fromFileName(logFileName))
					.toInputStream
				r <- drain &> parse(is)
			} yield r
		}
	}

	def justLogAsSubscriberWithFuture = {
		val r = WebClient.builder().options(cliOpts).build()
			.execute(req)
		r.subscribe(new Subscriber[HttpObject] {
			val logFile = new FileOutputStream(logFileName)

			override
			def onSubscribe(s: Subscription): Unit = {
				s.request(Long.MaxValue);
			}

			override
			def onNext(httpObject: HttpObject): Unit = httpObject match {
				case d: HttpData =>
					//println(s"INFO: onNext:" + d.length)
					logFile.write(d.array)
				case _ =>
			}

			override
			def onError(t: Throwable): Unit = {
				println(s"ERROR: $t")
				logFile.close()
			}

			override
			def onComplete(): Unit = {
				println(s"INFO: onComplete")
				logFile.close()
			}
		})
		Await.ready(r.whenComplete.asScala, Duration.Inf)
	}

	def unsafeRunSync[T](task: Task[T])(implicit runtime: Runtime[Any]) =
		Unsafe.unsafe { implicit u =>
			runtime.unsafe.run(task).getOrThrowFiberFailure
		}
}

class ArmeriaStreamTest extends AsyncWordSpec {
	import ArmeriaStreamTest._
	import zio.Runtime.default._
	import Endpoint._

/*	"java" in {
		val r = WebClient.builder().options(cliOpts).build()
			.execute(req)

		// Reads all cached
		r.toInputStream(mapper).asScala.map { r =>
			Using.resource(new FileOutputStream(logFileName)) { logFile =>
				val d: HttpData = r.content
				logFile.write(d.array)
				assert(d.length() > 20_000_000)
			}
		}
	}*/

	"zio" in {
		Unsafe.unsafe{ implicit u =>
			unsafe.runToFuture {
				getZIO
			}
		}.map { checkSize }
	}

/*	"sttp zio" in {
		import sttp.client3.SttpBackendOptions.ProxyType.Socks

		import armeria.zio.ArmeriaZioBackend
		val options = SttpBackendOptions(
			3.seconds,
			Some(SttpBackendOptions.Proxy("127.0.0.1", 7777, Socks)))

		val req = basicRequest.get(ccCoinList)
			.response(asStream(ZioStreams)(identity))//(_.toInputStream))

		Unsafe.unsafe { implicit u =>
			unsafe.runToFuture{
				//for{
					ArmeriaZioBackend().flatMap(
						_.send(req)).flatMap(_.body.fold( throw new Exception(_); null ))
				//} yield
			}
		}.map{ assert( true )}
	}*/
}
