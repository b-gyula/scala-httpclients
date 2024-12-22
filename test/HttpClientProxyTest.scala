import com.comcast.ip4s.{Host, Port}

import org.scalatest.matchers.must.Matchers
import org.scalatest.EitherValues._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.wordspec.AsyncWordSpec

import java.io.IOException
import java.util
import scala.concurrent.duration.DurationInt
import scala.language.{implicitConversions, postfixOps}
import play.api.Logging
import sttp.client3.UriContext
import sttp.model.Uri
import scala.jdk.FutureConverters._
import java.net.{InetSocketAddress, URI}
import java.net.InetSocketAddress.createUnresolved

class HttpClientProxyTest extends AsyncWordSpec with Matchers with ScalaFutures with Logging {
	val expectedIP = "144.21.33.32"
	val proxyPort = 7777
	val proxyHost = "127.0.0.1"
	val apifyUrl = uri"http://api.ipify.org"
	implicit def uriToString(uri: Uri): String = uri.toString

	"Armeria" in {
		import com.linecorp.armeria.client._
		import com.linecorp.armeria.client.proxy.ProxyConfig.socks5
		import com.linecorp.armeria.common._
		import com.linecorp.armeria.common.HttpMethod.GET
		val factory =
			ClientFactory.builder
				.proxyConfig(socks5(new InetSocketAddress(proxyHost, proxyPort)))
				.build
		val client = WebClient
			.builder(apifyUrl)
			.factory(factory)
			.maxResponseLength(40*1024*1024)
			.build
		val req = HttpRequest.of(GET, "/")
		client.execute(req)
			.aggregate.asScala
			.map( _.contentUtf8 mustBe expectedIP)
	}

	"Vertx" must { // OK https://www.techempower.com/benchmarks/#section=test&shareid=06639089-9b3f-4b71-b8f1-9a302df69d66&
		import io.vertx.core.buffer.Buffer
		import io.vertx.core.http.HttpClientResponse
		import io.vertx.core.net.ProxyType
		import io.vertx.core.Vertx

		import scala.language.implicitConversions

		import io.vertx.scala.core._
		import io.vertx.lang.scala._
		import io.vertx.core.http.HttpMethod
		import io.vertx.core.http.HttpResponseExpectation.SC_OK
		val proxyOptions = ProxyOptions(proxyHost, port = proxyPort, `type` = ProxyType.SOCKS5)
		val clientOptions = HttpClientOptions(
			//protocolVersion = HttpVersion.HTTP_2
			//,decompressionSupported = true // ERROR: Failed to find Brotli native library in classpath: /lib/windows-x86_64/brotli.dll
			//shared = true // v4.x!!! DOES NOT WORK !!!!
			useAlpn = true
			,proxyOptions = proxyOptions
		)

		"httpclient with for" in {
			val client = Vertx.vertx.createHttpClient(clientOptions)
			for {
				req <- client.request(RequestOptions(  apifyUrl
						//, proxyOptions = proxyOptions
					))
			//req <- client.request(HttpMethod.GET, "api.ipify.org", "/")
					.asScala

				res: HttpClientResponse <- req.send.expecting(SC_OK)
					.asScala

				b: Buffer <- res.body.asScala
			} yield b.toString("UTF-8") mustBe expectedIP
		}

		"httpclient compose" in {
			val client = Vertx.vertx.createHttpClient
			client.request(RequestOptions(  apifyUrl
						,proxyOptions = proxyOptions
					))
				.compose{_.send.expecting(SC_OK)}
				.compose{_.body}.asScala
				.map { b => b.toString("UTF-8") mustBe expectedIP }
		}

		"webclient" in {
			import io.vertx.ext.web.client.{WebClient, WebClientOptions}

			val client = WebClient.create(Vertx.vertx( VertxOptions()),
				new WebClientOptions(clientOptions))

			val req = client.request(HttpMethod.GET, apifyUrl,"/")
				//.proxy(proxyOptions)

			req.send
				.expecting(SC_OK).asScala
				.map(_.bodyAsString mustBe expectedIP)
		}
	}

	"ZIO http" in {
		import zio._
		import zio.http._
		import zio.http.Method.GET
		implicit def pathOrUrl(path: String): URL =
			if (path.startsWith("http://") || path.startsWith("https://")) {
				URL.decode(path).getOrElse(URL(Path(path)))
			} else {
				URL(Path(path))
			}

		//		val clientApp = for {
		//			dom   <- endpointExecutor()(endpoint())
		//		} yield dom
		val prg = for {
			client <- ZIO.serviceWith[Client](_.uri(new URI(apifyUrl))
				.proxy(Proxy(url"socks://$proxyHost:$proxyPort")) // DOES NOT WORK
				.batched)
			r <- client(Request(method = GET))
		} yield r.body.asString

		Unsafe.unsafe { implicit unsafe =>
			Runtime.default.unsafe.run {
					prg.provide(Client.default)
				}
				.getOrThrowFiberFailure mustBe expectedIP
		}
	}

/*	"Armeria" must {

		"stream" ignore { // Cannot handle 20M bytes: 10M buffer in aggregator
			import com.linecorp.armeria.client.WebClient
			import com.linecorp.armeria.common.HttpStatus.OK
			import com.linecorp.armeria.common._
			import java.nio.charset.StandardCharsets
			import scala.io.Source
			import scala.jdk.FutureConverters.CompletionStageOps
			val client = WebClient.of(cryptoCompareCoinListUri.host);
			val r = client.get(cryptoCompareCoinListUri.getPath)/*.mapData(data => {
				println(data.toString(StandardCharsets.UTF_8))
				data
			})
			r.aggregate(new AggregationOptionsBuilder().cacheResult(false).build()).asScala
			val is = r.aggregate().toInputStream {
				case r: HttpData => println(r.toString(StandardCharsets.UTF_8)); r
				case _: HttpHeaders => println(s"Headers"); HttpData.empty()
			}*/
			//				.map{ r=>
			//println(	readFromStream( is )		)
			//println(	Source.fromInputStream(is).mkString		)
			//				r.status mustBe OK
			//			}

			/*r.aggregate(AggregationOptionsBuilder).asScala
				.map{ r =>
					println( r.contentUtf8 )
					r.status mustBe OK
				}*/
			//.join
			assert(true)
		}
	}*/

	"sttp" can {
		import sttp.client3._
		import sttp.model.Uri
		import sttp.client3.SttpBackendOptions.ProxyType.Socks
		import scala.concurrent.{Await, Future}
		val options = SttpBackendOptions( 3.seconds,
			Some(SttpBackendOptions.Proxy(proxyHost, proxyPort, Socks)))

		val request = basicRequest.get(apifyUrl)
		/*		def checkSync[Identity[_], _](sttpBackend: SttpBackend[Identity, _]): Assertion = {
					val r = sttpBackend.send(request)
					r.body mustBe expectedIP
				}*/

		def check(backend: SttpBackend[Future, ?]) = {
			backend
				.send(request).map{ _.body.value mustBe expectedIP }
		}

		"simple" in {
			quick.backend
				.send(request).body.value mustBe expectedIP
		}

		"HttpClientSync+proxy" in {
			HttpClientSyncBackend(options)
				.send(request).body.value mustBe expectedIP
		}

		"HttpURLConnection" in { // OK
			HttpURLConnectionBackend(options)
				.send(request).body.value mustBe expectedIP
		}

/*		"AkkaHttpBackend" in {
			import sttp.client3.akkahttp._
			check(AkkaHttpBackend())
		}*/

		"PekkoHttpBackend" in {
			import sttp.client3.pekkohttp._
			check(PekkoHttpBackend(options))
		}

		"ArmeriaFutureBackend" in { // OK
			import sttp.client3.armeria._
			import future.ArmeriaFutureBackend
			check(ArmeriaFutureBackend(options))
		}

		"OKHttp" must {
			"sync" in { // OK
				import okhttp.OkHttpSyncBackend
				OkHttpSyncBackend(options)
					.send(request).body.value mustBe expectedIP
			}
		}


	}

	"http4s" in {
		import cats.effect.IO
		import cats.effect.unsafe.implicits._
		import org.http4s.netty.client.Socks5
		import org.http4s.netty.client.NettyClientBuilder

		NettyClientBuilder[IO]
			.withProxy(Socks5(Host.fromString(proxyHost).get,
				Port.fromInt(proxyPort).get))
			.resource
			.use(
				_.expect[String](apifyUrl)
			).unsafeToFuture
			.map(_ mustBe expectedIP)
	}

	"play-ws" in { // OK
		import play.shaded.ahc.org.asynchttpclient.proxy.{ProxyServer, ProxyType}
		import play.shaded.ahc.org.asynchttpclient._
		import play.api.libs.ws._
		import play.api.test.WsTestClient
		import scala.compat.java8.FutureConverters._
		import java.util.concurrent.{Future => JavaFuture}

		val asyncHttpClient = Dsl.asyncHttpClient( new DefaultAsyncHttpClientConfig.Builder()
			.setProxyServer(new ProxyServer.Builder(proxyHost, proxyPort).setProxyType(ProxyType.SOCKS_V4))
			.setMaxRequestRetry(0)
			.setShutdownQuietPeriod(0)
			.setShutdownTimeout(0))
		//val req = asyncHttpClient.prepareGet(apifyUrl).execute
		WsTestClient.withClient { cli: WSClient =>
			//val underlying = cli.underlying.asInstanceOf[AsyncHttpClient]
			//underlying.getConfig.
			val req = cli.url(apifyUrl)
				.withProxyServer(DefaultWSProxyServer(proxyHost, proxyPort, Some("socks5")))
			//val req = wsClient.url(apifyUrl)

			req.get.map(_.body mustBe expectedIP)
		}
	}

	"JDK HttpClient" in {
		import java.net.Proxy.Type
		import java.net.http.HttpClient
		import java.net.http.HttpClient._
		import java.net.{ProxySelector, Proxy,InetSocketAddress}
		import scala.collection.mutable.ArrayBuffer
		import java.net.http.HttpResponse.BodyHandlers
		import java.net.http.{ HttpRequest, HttpResponse}
		import java.net.{ SocketAddress, URI}
		import java.util.{List => jList}
		val proxy = new Proxy(Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort) )
		object JDKHttpClientProxySelector extends ProxySelector{
			protected val NO_PROXY_LIST: jList[Proxy] = jList.of[Proxy](Proxy.NO_PROXY)
			case class Mapper(url: URI, proxys: jList[Proxy]) {
				def matches(url: URI): Boolean = url.getScheme.equals(this.url.getScheme) &&
					url.getHost.equals(this.url.getHost)
			}
			protected val _map = ArrayBuffer.empty[Mapper]
			def reg(url: URI, proxy: Proxy) = {
				// TODO find duplicates
				_map.append(Mapper(url, jList.of[Proxy](proxy)))
			}
			override def select(uri: URI): util.List[Proxy] = {
				val p = _map.find(_.matches(uri)).map(_.proxys).getOrElse(NO_PROXY_LIST)
				jList.of[Proxy](proxy)
			}

			override def connectFailed(uri: URI, sa: SocketAddress, ioe: IOException): Unit = {
				println( (uri, sa, ioe) )
			}
		}
		val uri =apifyUrl.toJavaUri
		JDKHttpClientProxySelector.reg(uri, proxy)
		val client = HttpClient.newBuilder
			.version(Version.HTTP_2)
			.followRedirects(Redirect.NORMAL)
			.proxy(JDKHttpClientProxySelector)
			.build();
		val req = HttpRequest.newBuilder.uri(uri).build
		client.send( req , BodyHandlers.ofString).body mustBe expectedIP
	}

}