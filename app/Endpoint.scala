import com.github.plokhotnyuk.jsoniter_scala.core.{JsonValueCodec, readFromStream, readFromStreamReentrant}
import com.github.plokhotnyuk.jsoniter_scala.macros.CodecMakerConfig
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make
import io.vertx.core.{MultiMap, Vertx}
import io.vertx.core.buffer.Buffer
import io.vertx.core.http.{HttpClientRequest, HttpClientResponse}
import io.vertx.core.http.HttpResponseExpectation.SC_OK
import io.vertx.ext.reactivestreams.ReactiveWriteStream
import io.vertx.scala.core.RequestOptions
import play.api.Logging
import zio.{CancelableFuture, Chunk, Unsafe, ZIO}
import zio.stream.{ZPipeline, ZSink}

import java.io.InputStream
import scala.jdk.CollectionConverters.IterableHasAsScala

object Endpoint extends Logging {

	case class CCAsset( CoinName: String
							  ,IsTrading: Boolean = true
							  /*,Symbol: String
								 ,Description: String
								 ,AssetWebsiteUrl: Opt[String]
								 ,BuiltOn: Option[String]*/
							)

	case class Result(Data: Map[String, CCAsset])

	implicit def resultJsonCodec: JsonValueCodec[Result] = make(CodecMakerConfig.withMapMaxInsertNumber(18000))

	def headers2Str(hdrs: MultiMap): String = {
		hdrs.asScala.mkString
	}

	implicit def req2Str(r: HttpClientRequest): String =
		r.getMethod /*+ (if(r. == HttpProtocols.`HTTP/2.0`) "/2" else "")*/ + " " +
			r.getURI + " " + headers2Str(r.headers)

	implicit def resp2Str(r: HttpClientResponse): String = headers2Str(r.headers) + " "

	def get(uri: String, logFileName: String)(implicit resultJsonCodec: JsonValueCodec[Result]): CancelableFuture[Result] = {
		import zio.interop.reactivestreams._
		import zio.Runtime.default._
		val client = Vertx.vertx.createHttpClient
		val rws = ReactiveWriteStream.writeStream[Buffer](Vertx.vertx)
		val rwsZIO = ZIO.acquireRelease(	ZIO.succeed( rws ))(
			rws => ZIO.attemptBlocking(rws.close).orDie)

		val createReq = ZIO.fromCompletionStage(
				client.request( RequestOptions(uri)).toCompletionStage )
					//.compose( r =>
		def send(r: HttpClientRequest) = ZIO.fromCompletionStage(
				r.send.expecting(SC_OK).toCompletionStage )
			///.compose( r =>
		def pipeTo(r: HttpClientResponse) = ZIO.fromCompletionStage (
				r.pipeTo(rws).toCompletionStage	)

		def res(is: InputStream) = ZIO.attempt {
			readFromStream(is)
		}

		def log(req: HttpClientRequest, resp: HttpClientResponse, result: Either[Throwable, String], logFileName: String) = {
			val l = s"$req: $resp"
			result.fold(e => {
					logger.error(l + e.getMessage, e)
				}
				, r => {
					logger.debug(l + r)
				})
		}

		Unsafe.unsafe { implicit u =>
			unsafe.runToFuture {
				ZIO.scoped {
					for {
						rws <- rwsZIO
						req <- createReq
						resp <- send(req)
						is <- rws.toZIOStream(2) // FIXME Handle non UTF-8 encoding
							.mapConcatChunk(b => Chunk.fromArray(b.getBytes)) // Makes copy!!
//							.tapSink(ZSink.fromFileName(logFileName))
//							.tapSink(ZSink.take(512).map(c =>
//												log(req, resp, Right(new String(c.toArray, "UTF-8")), logFileName))) // Truncated log
							.toInputStream
						r <- pipeTo(resp) &> res(is)
						//r <- createReq &> res(is)
					} yield r
				}.mapErrorCause { t =>
					//log(req, resp, Left(t), logFileName)
					//ZIO.attempt{
					logger.error("Error: " + t.prettyPrint)
					//}
					t
				}
			}//.getOrThrowFiberFailure()
		}
	}
}