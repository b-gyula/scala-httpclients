
import Endpoint._
import com.github.plokhotnyuk.jsoniter_scala.core.readFromStream
import com.linecorp.armeria.client.encoding.DecodingClient
import com.linecorp.armeria.client.proxy.ProxyConfig.{connect, socks5}
import com.linecorp.armeria.client.{ClientFactory, ClientOptions, WebClient}
import com.linecorp.armeria.common._
import org.reactivestreams.{Subscriber, Subscription}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import sttp.model.Headers
import zio.{Chunk, Ref, Task}
import zio.stream.{ZSink, ZStream}

import java.io.{FileOutputStream, InputStream}
import java.net.{InetSocketAddress, URI}
import java.nio.charset.Charset
import java.nio.file.Path
import java.nio.charset.StandardCharsets.UTF_8
import java.util.function.Function
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.jdk.FutureConverters._


object ArmeriaStreamTest extends StreamTest {

	val uriCryptoCompare = URI.create ("https://min-api.cryptocompare.com") //"https://pro-api.coinmarketcap.com")
	val maxResponseLength = 40*1024*1024
	val proxyPort = 8080
	val path = "/data/all/coinlist"

	val headers = RequestHeaders.builder(HttpMethod.GET, path)
						.accept(MediaType.JSON)
						.build

//	val req = HttpRequest.builder
////		.scheme(ccCoinList.scheme.get)
////		.authority(ccCoinList.host.get)
////		.method(HttpMethod.GET)
////		.path(path)
//		.headers(headers) // Method + path in headers
//		.build

	val cliOpts = ClientOptions.builder.maxResponseLength(maxResponseLength)
		.build

	val clientFactory = ClientFactory.builder
		//.proxyConfig(connect(new InetSocketAddress(proxyHost, proxyPort)))
		.tlsNoVerify
		.build

	val cli = //clientFactory.newClient(ClientBuilderParams.of(ccCoinList, classOf[WebClient], cliOpts)).asInstanceOf[WebClient]
		WebClient.builder(uriCryptoCompare)
			.options(cliOpts)
			.factory(clientFactory)
			.decorator(
				DecodingClient // gzip support
					.builder()
					.autoFillAcceptEncoding(false)
					.strictContentEncoding(true)
					.newDecorator)
			.build

	def main(args: Array[String]): Unit = if(args.length > 0) args(0) match {
		case "s" => runZIO(sttpZIO)
						//unsafeRunSync( sttpZIO )
		case "z" => runZIO(myZIO)
		case "t" => runZIO(tapZIO)
	}
	else {
		toInputStream
		//clientFactory.close()
	}

	def toInputStream = { // Fully blocking
		val r = cli.execute(headers).split
		val duplicator = r.body.toDuplicator
		duplicator.duplicate.writeTo(Function.identity, Path.of(logFileName("toInputStream")))
		val is = duplicator.duplicate.toInputStream(Function.identity)
		println(readFromStream(is).Data.size)
	}

	def runZIO(e: zio.Task[Result]) = {
		val r = unsafeRunSync (e)
		println(r.Data.size)
	}

	//val executor =	fromJavaExecutor(newCachedThreadPool)
	def parse(is: InputStream) = zio.ZIO.attemptBlocking {
		readFromStream(is)
	}

	/** Use broadcast to parallel process file log + parsing  */
	def myZIO = {
		import zio._
		import zio.interop.reactivestreams._
		import zio.stream.ZSink

		ZIO.scoped {
			for {
				resp <- ZIO.attemptBlocking(cli.execute(headers).split)
				s <- resp.body.toZIOStream(2) // Limit buffer buffer
					.mapConcatChunk { d => Chunk.fromArray(d.array) }
					.broadcast(2,1)
				is <- s(0)
					.toInputStream
				r <- parse(is) <& s(1).run(ZSink.fromFileName(logFileName("myZIO"))).logError
			} yield r
		}
	}

	/** Use tapSink to parallel process file log + parsing */
	def tapZIO = {
		import zio._
		import zio.interop.reactivestreams._
		import zio.stream.ZSink

		ZIO.scoped {
			for {
				resp <- ZIO.attemptBlocking(cli.execute(headers).split)
				is <- resp.body.toZIOStream(2) // Limit buffer buffer
					.mapConcatChunk { d => Chunk.fromArray(d.array) }
					.tapSink(ZSink.fromFileName(logFileName("tapZIO")))
					.toInputStream
				r <- parse(is)
			} yield r
		}

	}

	/** Use sttp.ArmeriaZioBackend: Adds error handling */
	def sttpZIO:Task[Result] = {
		import sttp.client3._
		import sttp.model.Method.GET
		import armeria.ArmeriaWebClient
		import SttpBackendOptions.ProxyType.{Socks, Http}
		import scala.concurrent.duration.DurationInt
		import sttp.capabilities.zio.ZioStreams
		val options = SttpBackendOptions(
			3.seconds,
			Some(SttpBackendOptions.Proxy(proxyHost, proxyPort, Http)))
		//val req = basicRequest.get(uri"http://pro-api.coinmarketcap.com") // How to set method?
		val req = //basicRequest.get(uri"$path")
			//val req = basicRequest.get(Uri(uriCryptoCompare).withWholePath("/data/all/coinlist"))
			emptyRequest.method(GET, uri"$path")
			.response( // Uses toZIOStream() with default *16*
				asStreamUnsafe(ZioStreams)
//					.mapWithMetadata {
//						(t,m) => t.left.foreach ( e =>
//							logger.error(s"$req > $e ${Headers.toStringSafe(m.headers)}")
//						)
//						t
//					}
//				.getRight
			)

		import armeria.zio.ArmeriaZioBackend
		import zio.ZIO
		var trimmedBody = ""
		ZIO.scoped {
			for { //options,
				//backend <- ArmeriaZioBackend.usingClient(ArmeriaWebClient.newClient(_.options(cliOpts).factory(clientFactory)))
				backend <- ArmeriaZioBackend.usingClient(cli) // Use clientFactory managed by us. Creates redirecting client -> DOES NOT WORK! not passing Uri properly
				resp <- backend.send(req)/*.tapError { e => logger.error(req.show() + " > " + e)
																		ZIO.fail(e)	}*/
				//x <- resp.body.fold(ZIO.fail(_),
				//trimmedBody <- Ref.make(Chunk.empty[Byte])
				enc = resp.contentType.map( Charset.forName).getOrElse(UTF_8)
				is <- resp.body.fold( e => { // Error handling
						logger.error(s"$req > ${resp.code} $e ${resp.headers}")
						ZStream.fail(HttpError(e, resp.code))
					},
				//_.run(ZSink.fromFileName(logFileName("sttp")))
				//)
					_.tapSink(ZSink.fromFileName(logFileName("sttp")))) // Makes copy!
					.tapSink(ZSink.take(128).map{c: Chunk[Byte] => trimmedBody = c.asString(enc)}.ignoreLeftover)
					.toInputStream // TODO check if not UTF-8 Encoding
				r <- parse(is)
			} yield {
				logger.debug(req.show() + " > " + trimmedBody + resp.headers)
				r
			}
		}
		//{_ => logger.debug(req.show())}
	}

	def future = {
			import sttp.client3.armeria.future.ArmeriaFutureBackend // DOES not support streaming
			//ArmeriaFutureBackend.usingClient(cli).send(req)
	}

	def justLogAsSubscriberWithFuture = {
		val r = WebClient.builder.options(cliOpts).build
			.execute(headers)
		r.subscribe(new Subscriber[HttpObject] {
			val logFile = new FileOutputStream(logFileName("subs_fut"))

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
}

class ArmeriaStreamTest extends AsyncWordSpec with Matchers with ScalaFutures {
	import ArmeriaStreamTest._

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
		unsafeRunFuture{
			myZIO
		}.map { checkSize }
	}

/*	"sttp zio" in {
		unsafeRunFuture {
			sttpZIO
		}.map{ checkSize }
	}*/
}
