import Endpoint.Result
import com.github.plokhotnyuk.jsoniter_scala.core.readFromStream
import io.cloudonix.vertx.javaio.WriteToInputStream
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.HttpMethod._
import io.vertx.core.http.HttpResponseExpectation.SC_OK
import io.vertx.core.http.{HttpClientRequest, HttpClientResponse, HttpVersion}
import io.vertx.core.net.ProxyType.SOCKS5
import io.vertx.core.streams.WriteStream
import io.vertx.core.{AsyncResult, Handler, Vertx}
import io.vertx.lang.scala._
import io.vertx.lang.scala.conv._
import io.vertx.scala.core._
import org.scalatest
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AsyncWordSpec
import zio.{Runtime, Task}
import zio.stream.ZSink

import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.text.SimpleDateFormat
import java.util.Date
import scala.concurrent.duration.DurationInt
import scala.concurrent.{Await, Future, blocking}
import scala.jdk.FunctionConverters._
import scala.language.{implicitConversions, postfixOps}
import scala.util.Try

trait StreamTest {
	val expSize = 16035
	val fileTimeFmt = new SimpleDateFormat("yy-MM-dd_HH.mm.ss")
	def checkSize(r: Result) = {
		println (r.Data.size)
		scalatest.Assertions.assert(r.Data.size >= expSize)
	}

	def logFileName: String = this.getClass.getSimpleName + "_cryptoCompareCoinList@" + fileTimeFmt.format(new Date()) + ".json"

	def unsafeRunSync[T](task: Task[T])(implicit runtime: Runtime[Any]) =
		zio.Unsafe.unsafe { implicit u =>
			runtime.unsafe.run(task).getOrThrowFiberFailure
		}
}

object VertXHttpClientStreamTest extends StreamTest {
	import Endpoint._
	import scala.concurrent.ExecutionContext.Implicits.global
	val cryptoCompareCoinListUri = "https://min-api.cryptocompare.com/data/all/coinlist"

	def printResult(b: Buffer): Unit = println(b.toString("UTF-8"))

	val proxyOptions = ProxyOptions("127.0.0.1", port = 1080, `type` = SOCKS5)

	implicit class AsyncResultOps[T](a: AsyncResult[T]) {
		def asFuture: Future[T] = if(a.succeeded) Future.successful(a.result) else Future.failed(a.cause)
	}

	//implicit def asyncToFuture[T](a: AsyncResult[T]): Future[T] = a.asFuture
	implicit def funcToHandler[T](f: AsyncResult[T] => Unit): Handler[AsyncResult[T]] =
		(event: AsyncResult[T]) => f(event)

	implicit class futureMapCorrect[T](f: VertxFuture[T] ) {
		def mapWith[U](mapper: T => U): VertxFuture[U] = f.map( mapper.asJava).asInstanceOf[VertxFuture[U]]
	}

	val client = Vertx.vertx.createHttpClient(HttpClientOptions())

	def getFut: Future[(HttpClientResponse, Result)] = {
		val stream = new WriteToInputStream(Vertx.vertx)
		val resp = client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose { _.send.expecting(SC_OK) }
			.compose { r => r.pipeTo(stream).mapWith{_ => r} }.asScala
		def parse = Future {
			readFromStream(stream)
		}
		resp.zip(parse)// Running them parallel avoids reading everything into the memory
	}

	def main(args: Array[String]): Unit = {
		Await.ready(getFut.map {
			case (resp, r) => println(r.Data.size)}
			, 5 second)
	}

	object withZIO {
		import zio._

		val createReq = ZIO.fromCompletionStage(
			client.request(RequestOptions(cryptoCompareCoinListUri)).toCompletionStage )

		def parse(is: InputStream) = ZIO.attempt {
			readFromStream(is)
		}

		def sendNPipe(r: HttpClientRequest, rws: WriteStream[Buffer]) = ZIO.fromCompletionStage {
			val resp = r.send.expecting(SC_OK) //.toCompletionStage
			resp.compose { r => // resp has to compose with pipeTo!!!!
					r.pipeTo(rws).compose(_ => resp)}
				.toCompletionStage
		}

		def useWriteToInputStream = {
			val stream = ZIO.acquireRelease(	ZIO.succeed( new WriteToInputStream(Vertx.vertx) ))(
				rws => ZIO.attemptBlocking(rws.close).orDie)
			ZIO.scoped {
				for {
					strm <- stream
					req <- createReq
					//resp <- send(req)
					//r <- pipeTo(resp, strm) &> res(strm)
					r <- sendNPipe(req, strm) &> parse(strm)
				} yield r
			}
		}
	}
}

class VertXHttpClientStreamTest extends AsyncWordSpec with Matchers with ScalaFutures with StreamTest{
	import Endpoint._
	import VertXHttpClientStreamTest._

	"client options" ignore {
		val defHttpClientOptions = HttpClientOptions(
			decompressionSupported = true
			,defaultPort = 443
			,ssl = true
			,protocolVersion = HttpVersion.HTTP_2
			,useAlpn = true // Needed 4 HTTP2
			//,logActivity = true // Logs every byte
		)
		Vertx.vertx.createHttpClient(
				new HttpClientOptions(defHttpClientOptions)
					.setDefaultHost( "min-api.cryptocompare.com")
					.setTrustAll(true)// !!! Just 4 TEST !!!
					.setProxyOptions(proxyOptions)//)) // When needed
			)
			.request(GET,"/data/all/coinlist")
			.compose(_.send)
			.compose(_.body()).asScala
			.map(b => assert( b.toString.length > 20 * 1024 * 1024) )
	}


	/* WriteToInputStream collects vertx`Buffer`s as is while not read uses CountDownLatch for wait */
	"WriteToInputStream" in { // OK !!!!
		getFut.map { // Running them parallel avoids reading everything into the memory
			case (resp, r) => //println(r)
				checkSize( r)
		}
	}

	/* InputStreamAdapter `append`s (copy) `Buffer`s not yet read */
/*	"InputStreamAdapter serial" in { //Blocks thread
		import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter
		import java.nio.file.{Files, Path, StandardCopyOption}
		import io.vertx.lang.scala.VertxFutureConverter

		client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose(_.send.expecting(SC_OK)	)
			//.compose( _.end() )
			//Future{ r => Files.copy(new InputStreamAdapter(r),Path.of(logFileName)) }
			.mapWith( r => readFromStream(new InputStreamAdapter(r)) ).asScala
			.map{ checkSize }
			//.map{ l => assert (l > 0) }

	}*/

	"InputStreamAdapter future" in { // SKIPS first ~15Kb
		import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter
		println("Start in: " + Thread.currentThread.getName)
		val resp = client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose {println("resp in: " + Thread.currentThread.getName)
				_.send.expecting(SC_OK)}.asScala
		//.map(x => x.end.asScala -> readFromStream(new InputStreamAdapter(x, 1024))	)

		/*		java.lang.IllegalStateException was thrown.
			*/

		def result(r: HttpClientResponse) = Future { blocking {
			println("#result in: " + Thread.currentThread.getName)
			readFromStream/*Reentrant*/(new InputStreamAdapter(r))
		}}
		println("Start in: " + Thread.currentThread.getName)
		resp.flatMap { re =>
			re.end.asScala.zip(result(re))
		}.map{case (_,r) => checkSize( r )}
		/* Infinite loop
		whenReady({
			resp.flatMap { re =>
				result(re)
				re.end.asScala
		}
		,timeout(20 seconds))(_ => assert(true))
		*/
		/*	Reads first ~15Kb
					val resp = client.request(RequestOptions(cryptoCompareCoinListUri))
						.compose{_.send.expecting(SC_OK)	}.asScala
						.map(x => (readFromStream(new InputStreamAdapter(x, 1024)) , x))

					(for {
						 (r,res) <- resp
						e <- res.end.asScala
						//r = readFromStream(new InputStreamAdapter(res, 1024))
					} yield (r, e)
						).map { case (rx: Result, end: Void) =>
						println(rx)
						assert(true)
					}
					*/
		/*	Reads first ~15Kb
						client.request(RequestOptions( cryptoCompareCoinListUri))
						.compose{ _.send.expecting(SC_OK)}.asScala
						.map(r => (readFromStream(new InputStreamAdapter(r, 1024)), r))
						.flatMap { case (r, resp) =>
							println(r)
							resp.end.asScala.map { _ => assert(true) }
						}*/
		/*	Infinite loop
		client.request(RequestOptions( cryptoCompareCoinListUri))
		.compose{ _.send.expecting(SC_OK)}.asScala
		.compose { r =>
			println (readFromStream(
				new InputStreamAdapter(r, 1024)
			)); r.end }.asScala
		.map(_ => assert(true))*/
	}

	"ZIO" must {
		import zio.Runtime.default._
		import zio._
		import withZIO._
		"ZStream" in { //
			get(cryptoCompareCoinListUri + "", logFileName)
				.map { checkSize }
		}

		"sttp adapter" ignore { // DOES NOT WORK!!!
			import sttp.tapir.server.vertx.zio.VertxZioServerOptions.default
			import sttp.tapir.server.vertx.zio.streams.zioReadStreamCompatible
			val rs = zioReadStreamCompatible(default)(Runtime.default)
			def send(r: HttpClientRequest) =  ZIO.fromCompletionStage(
				r.send.expecting(SC_OK).toCompletionStage )
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					ZIO.scoped {
						for {
							req <- createReq
							resp <- send(req)
							is <- rs.fromReadStream(resp, None)
										.tapSink(ZSink.fromFileName(logFileName))
										.toInputStream
							r <- parse(is) <& ZIO.fromCompletionStage(resp.end.toCompletionStage)
						} yield r
					}
				}
			}.map{ checkSize }
		}

		// UNSTABLE!!!
		// Best: low memory: max 4 buffer(8K),  scala-jsoniter `readFromStream` waits in ZIO
		"through reactive stream" in {
			import io.vertx.ext.reactivestreams.ReactiveWriteStream
			import zio.interop.reactivestreams._
			val rwsZIO = ZIO.acquireRelease(	ZIO.succeed( ReactiveWriteStream.writeStream[Buffer](Vertx.vertx) ))(
				rws => ZIO.attemptBlocking(rws.close).orDie)
			val sb = new StringBuilder
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					ZIO.scoped {
						for {
							rws <- rwsZIO
							req <- createReq
							is <- rws.toZIOStream(2) // Max 2 buffer -> low memory
								/*.mapConcatChunk { b =>
									val bb = b.asInstanceOf[BufferImpl].byteBuf
									println(s"arrayOffset: ${bb.arrayOffset}, readerIndex: ${bb.readerIndex}, writerIndex: ${bb.writerIndex}, array len: ${bb.array.length}, ${bb}")
									Chunk.ByteArray(bb.array, bb.arrayOffset, bb.array.length - bb.arrayOffset)
								} */// No copy but has header also
								.mapConcatChunk {
									b =>
										if (b.length() < 1) println("WARNING: 0 length:" + b.toString(UTF_8))
										sb ++= "," + b.length
										Chunk.fromArray(b.getBytes) // Makes copy!!
								}
								.tapSink(ZSink.fromFileName(logFileName))
								.toInputStream
							r <- sendNPipe(req, rws) <&> parse(is)
						} yield r
					}
				}
			}.transform{t:Try[_] => println("Sizes:" + sb.result);t}.map {case (_, r: Result) =>	checkSize (r) }
		}

		"WriteToInputStream" in { // OK !!!!
/*
			def pipeTo(r: HttpClientResponse, stream: WriteToInputStream ) = ZIO.fromCompletionStage (
					 r.pipeTo(stream).toCompletionStage // Has to go together with send
			)
*/
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					useWriteToInputStream
				}
			}.map{ checkSize }
		}

		"InputStreamAdapter" ignore {
			import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter

			val resp = ZIO.fromCompletionStage( client.request(RequestOptions(cryptoCompareCoinListUri))
				.compose { _.send.expecting(SC_OK) }.toCompletionStage
			)
			def res(r: HttpClientResponse) = ZIO.attemptBlocking {
				println("#result")
				readFromStream(new InputStreamAdapter(r))
			}
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					for {
						re <- resp.debug
						r <- res(re) <& ZIO.fromCompletionStage(re.end.toCompletionStage)
					} yield r //expected '{', offset: 0x00000000, buf: "IsUsedInNft":0,
				}
			}.map{ checkSize }
		}
	}
}
