import com.github.plokhotnyuk.jsoniter_scala.core.{readFromStream, readFromStreamReentrant, scanJsonArrayFromStream}
import io.cloudonix.vertx.javaio.WriteToInputStream
import io.vertx.core.{AsyncResult, Handler, Vertx}
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpClientRequest, HttpClientResponse}
import io.vertx.core.http.HttpResponseExpectation.SC_OK
import io.vertx.core.streams.{ReadStream, WriteStream}
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
import io.vertx.lang.scala.conv._
import zio.stream.ZSink

import java.io.InputStream
import scala.jdk.FunctionConverters._

class VertXHttpClientStreamTest extends AsyncWordSpec with Matchers with ScalaFutures{
	import Endpoint._
	val cryptoCompareCoinListUri = "https://min-api.cryptocompare.com/data/all/coinlist"
	val expSize = 15902
	val fileTimeFmt = new SimpleDateFormat("yy-MM-dd_HH.mm.ss")
	def checkSize(r: Result) = assert (r.Data.size >= expSize)
	def logFileName: String = "cryptoCompareCoinList@" + fileTimeFmt.format(new Date()) + ".json"

	def printResult(b: Buffer): Unit = println(b.toString("UTF-8"))

	def dump(readStream: ReadStream[?]): Unit = {

	}

	implicit class AsyncResultOps[T](a: AsyncResult[T]) {
		def asFuture: Future[T] = if(a.succeeded) Future.successful(a.result) else Future.failed(a.cause)
	}

	val client = Vertx.vertx.createHttpClient(HttpClientOptions())

	//implicit def asyncToFuture[T](a: AsyncResult[T]): Future[T] = a.asFuture
	implicit def funcToHandler[T](f: AsyncResult[T] => Unit): Handler[AsyncResult[T]] =
		(event: AsyncResult[T]) => f(event)

	implicit class futureMapCorrect[T](f: VertxFuture[T] ) {
		def mapWith[U](mapper: T => U): VertxFuture[U] = f.map( mapper.asJava).asInstanceOf[VertxFuture[U]]
	}

	/* WriteToInputStream collects vertx`Buffer`s as is while not read */
	"httpclient stream WriteToInputStream" in { // OK !!!!

		val stream = new WriteToInputStream(Vertx.vertx)
		val resp = client.request(RequestOptions(cryptoCompareCoinListUri))
			.compose { _.send.expecting(SC_OK) }
			.compose { _.pipeTo(stream) }.asScala
		val res = Future { blocking {
			readFromStream(stream)
		}
		}
		sequence(Seq(resp, res)).map{ // Running them parallel avoids reading everything into the memory
			r => //println(r)
				checkSize( r(1).asInstanceOf[Result])
		}
	}

	/* InputStreamAdapter `append`s (copy) `Buffer`s not yet read */
	"httpclient stream InputStreamAdapter serial" in { //Blocks thread
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

	}

	"httpclient stream InputStreamAdapter" in { // SKIPS first ~15Kb
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

		val createReq = ZIO.fromCompletionStage(
			client.request(RequestOptions(cryptoCompareCoinListUri)).toCompletionStage )

		def res(is: InputStream) = ZIO.attempt {
			readFromStream(is)
		}

		def sendNPipe(r: HttpClientRequest, rws: WriteStream[Buffer]) = ZIO.fromCompletionStage {
			val resp = r.send.expecting(SC_OK) //.toCompletionStage
			resp.compose { r => // resp has to compose with pipeTo!!!!
					//def pipeTo(r: HttpClientResponse, rws: ReactiveWriteStream[Buffer]) = ZIO.fromCompletionStage (
					r.pipeTo(rws).compose(_ => resp)}
				.toCompletionStage
		}

		"ZStream" in {
			get(cryptoCompareCoinListUri + "", logFileName)
				.map { checkSize }
		}

		"through reactive stream" in { // Best: low memory: max 4 buffer(8K),  scala-jsoniter `readFromStream` waits in ZIO
			import io.vertx.ext.reactivestreams.ReactiveWriteStream
			import zio.interop.reactivestreams._
			val rwsZIO = ZIO.acquireRelease(	ZIO.succeed( ReactiveWriteStream.writeStream[Buffer](Vertx.vertx) ))(
				rws => ZIO.attemptBlocking(rws.close).orDie)

			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					ZIO.scoped {
						for {
							rws <- rwsZIO
							req <- createReq
							//resp <- send(req)
							//resp <- send
							is <- rws.toZIOStream(2) // Max 2 buffer -> low memory
								/*.mapConcatChunk { b =>
									val bb = b.asInstanceOf[BufferImpl].byteBuf
									println(s"arrayOffset: ${bb.arrayOffset}, readerIndex: ${bb.readerIndex}, writerIndex: ${bb.writerIndex}, array len: ${bb.array.length}, ${bb}")
									Chunk.ByteArray(bb.array, bb.arrayOffset, bb.array.length - bb.arrayOffset)
								} */// No copy but has header also
								.mapConcatChunk(b => Chunk.fromArray(b.getBytes)) // Makes copy!!
								.tapSink(ZSink.fromFileName(logFileName))
								.toInputStream
							r <- sendNPipe(req, rws) <&> res(is)
						} yield r
					}
				}
			}.map {case (_, r: Result) => checkSize (r) }
		}

		"httpclient stream WriteToInputStream" in { // OK !!!!

			val stream = ZIO.acquireRelease(	ZIO.succeed( new WriteToInputStream(Vertx.vertx) ))(
				rws => ZIO.attemptBlocking(rws.close).orDie)

			// .compose {
			def send(r: HttpClientRequest) =  ZIO.fromCompletionStage(
		 		r.send.expecting(SC_OK).toCompletionStage )
				//.compose {
			def pipeTo(r: HttpClientResponse, stream: WriteToInputStream ) = ZIO.fromCompletionStage (
					 r.pipeTo(stream).toCompletionStage // Has to go together with send
			)

			Unsafe.unsafe { implicit u =>
				unsafe.runToFuture {
					ZIO.scoped {
						for {
							strm <- stream
							req <- createReq
							//resp <- send(req)
							//r <- pipeTo(resp, strm) &> res(strm)
							r <- sendNPipe(req, strm) &> res(strm)
						} yield r
					}
				}
			}.map{ checkSize }
		}

		"httpclient stream InputStreamAdapter" in {
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
