import com.github.plokhotnyuk.jsoniter_scala.core.{readFromStream, readFromStreamReentrant, scanJsonArrayFromStream}
import io.cloudonix.vertx.javaio.WriteToInputStream
import io.vertx.core.{AsyncResult, Handler, Vertx}
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpClientRequest, HttpClientResponse}
import io.vertx.core.http.HttpResponseExpectation.SC_OK
import io.vertx.core.streams.ReadStream
import io.vertx.scala.core.RequestOptions
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AsyncWordSpec

import scala.language.{implicitConversions, postfixOps}
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import scala.concurrent.{Future, blocking}
import scala.concurrent.Future.sequence
import io.vertx.scala.core._
import io.vertx.lang.scala._
import zio.stream.ZSink

import java.io.InputStream
import java.util.concurrent.Flow
import scala.Seq

class VertXHttpClientStreamTest extends AsyncWordSpec with Matchers with ScalaFutures{
	import Endpoint._
	val cryptoCompareCoinListUri = "https://min-api.cryptocompare.com/data/all/coinlist"
	val expSize = 14902
	val fileTimeFmt = new SimpleDateFormat("yy-MM-dd_HH.mm.ss")
	def checkSize(r: Result) = assert (r.Data.size >= expSize)
	def logFileName: String = "cryptoCompareCoinList@" + fileTimeFmt.format(new Date()) + ".json"

	def printResult(b: Buffer): Unit = println(b.toString("UTF-8"))
	def dump(readStream: ReadStream[?]): Unit = {

	}
	implicit class AsyncResultOps[T](a: AsyncResult[T]) {
		def asFuture: Future[T] = if(a.succeeded) Future.successful(a.result) else Future.failed(a.cause)
	}

	//implicit def asyncToFuture[T](a: AsyncResult[T]): Future[T] = a.asFuture
	implicit def funcToHandler[T](f: AsyncResult[T] => Unit): Handler[AsyncResult[T]] =
		(event: AsyncResult[T]) => f(event)

	/* WriteToInputStream collects vertx`Buffer`s as is while not read */
	"httpclient stream WriteToInputStream" in { // OK !!!!
		val client = Vertx.vertx.createHttpClient
		val stream = new WriteToInputStream(Vertx.vertx)
		val resp = client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose { _.send.expecting(SC_OK) }
			.compose { _.pipeTo(stream) }.asScala
		val res = Future { blocking {
			//scanJsonArrayFromStream()
			readFromStream(stream)
		}
		}
		sequence(Seq(resp, res)).map{ // Running them parallel avoids reading everything into the memory
			r => //println(r)
				checkSize( r(1).asInstanceOf[Result])
		}
	}

	/* InputStreamAdapter `append`s (copy) `Buffer`s not yet read */
	"httpclient stream InputStreamAdapter serial" in {
		import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter
		import java.nio.file.{Files, Path, StandardCopyOption}
		val client = Vertx.vertx.createHttpClient

		client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose {	_.send.expecting(SC_OK)	 }.asScala
			//				.map{ r => Files.copy(new InputStreamAdapter(r)
			//										,Path.of(logFileName))
			//										 }
			.map{ r => readFromStream(new InputStreamAdapter(r)) }
			.map{ checkSize }

	}

	"httpclient stream InputStreamAdapter" in { // SKIPS first ~15Kb
		import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter
		val client = Vertx.vertx.createHttpClient
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
			sequence(Seq(result(re), re.end.asScala))
		}.map(r => checkSize( r(1).asInstanceOf[Result]))
		/* Inifinite loop
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
		import zio._
		import zio.Runtime.default._

		"ZStream" in {
			get(cryptoCompareCoinListUri + "", logFileName)
				.map { checkSize }
		}

		"through reactive stream" in {
			import io.vertx.ext.reactivestreams.ReactiveWriteStream
			import zio.interop.reactivestreams._
			val client = Vertx.vertx.createHttpClient
			val rwStrm = ReactiveWriteStream.writeStream[Buffer](Vertx.vertx)
			val rwsZIO = ZIO.acquireRelease(	ZIO.succeed( rwStrm ))(
				rws => ZIO.attemptBlocking(rws.close).orDie)
			val createReq = ZIO.fromCompletionStage(
				client.request( RequestOptions(cryptoCompareCoinListUri))
					.toCompletionStage )
			//		.compose( r =>
			//def send(r: HttpClientRequest) = ZIO.fromCompletionStage {
			def send(r: HttpClientRequest, rws: ReactiveWriteStream[Buffer]) = ZIO.fromCompletionStage {
				val resp = r.send.expecting(SC_OK) //.toCompletionStage
				resp.compose { r => // resp has to compose with pipeTo!!!!
						//def pipeTo(r: HttpClientResponse, rws: ReactiveWriteStream[Buffer]) = ZIO.fromCompletionStage (
						r.pipeTo(rws).compose(_ => resp)}
							.toCompletionStage
			}

			def res(is: InputStream) = ZIO.attempt {
				readFromStream(is)
			}
			var logPerfix = ""
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					ZIO.scoped {
						for {
							rws <- rwsZIO
							req <- createReq
							//resp <- send(req)
							//resp <- send
							is <- rws.toZIOStream(2)
								.mapConcatChunk(b => Chunk.fromArray(b.getBytes)) // Makes copy!!
								.tapSink(ZSink.fromFileName(logFileName))
								.tapSink(ZSink.take(512).map(c => logPerfix = new String(c.toArray, "UTF-8")))
								.toInputStream
							//r <- pipeTo(resp, rws) &> res(is)
							r <- send(req, rws) <&> res(is)
							//r <- ZIO.fromCompletionStage (resp.pipeTo(rws).toCompletionStage) &> res(is)
						} yield r
					}
				}
			}.map {case (_, r: Result) => println (logPerfix); checkSize (r) }
		}

		"httpclient stream WriteToInputStream" in { // OK !!!!
			val client = Vertx.vertx.createHttpClient
			val stream = new WriteToInputStream(Vertx.vertx)

			val res = ZIO.attempt {
				readFromStream(stream)
			}

			val resp = ZIO.fromCompletionStage(
				client.request(RequestOptions(cryptoCompareCoinListUri))
					.compose { _.send.expecting(SC_OK) }
					.compose { _.pipeTo(stream) }.toCompletionStage
			)
			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					for {
						r <- resp &> res
					} yield r
				}
			}.map{ checkSize }
		}

		"httpclient stream InputStreamAdapter" in {
			import org.jboss.resteasy.client.jaxrs.engines.vertx.InputStreamAdapter
			val client = Vertx.vertx.createHttpClient
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
						r <- res(re).debug //<& ZIO.fromCompletionStage(re.end.toCompletionStage)
					} yield r //expected '{', offset: 0x00000000, buf: "IsUsedInNft":0,
				}
			}.map{ checkSize }
		}
	}
}
