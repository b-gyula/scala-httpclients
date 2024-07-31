
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.play.PlaySpec
import org.scalatest.EitherValues._

import java.io.IOException
import java.util
import scala.concurrent.duration.DurationInt


class HttpClientTest extends PlaySpec with Matchers {
	val expectedIP = "144.21.33.32"

	"Vertx" in { // OK https://www.techempower.com/benchmarks/#section=test&shareid=06639089-9b3f-4b71-b8f1-9a302df69d66&
		import io.vertx.core.http._
		import io.vertx.ext.web.client._
		import io.vertx.core.http.HttpVersion
		import io.vertx.core.net.ProxyType
		import io.vertx.core.net.ProxyOptions
		import io.vertx.core._
		import scala.language.implicitConversions
		import io.vertx.core.buffer.Buffer

		val client = WebClient.create(Vertx.vertx(new VertxOptions()),
			new WebClientOptions()
				.setProtocolVersion(HttpVersion.HTTP_2)
				//.setTryUseCompression(true)
				.setDecompressionSupported(true)
				.setShared(true))

		val req = client.request(HttpMethod.GET, "api.ipify.org","/")
			.proxy(new ProxyOptions()
				.setHost("127.0.0.1")
				.setPort(8888)
				.setType(ProxyType.SOCKS5))

		val f = req.send
			.expecting(HttpResponseExpectation.SC_SUCCESS)
			.toCompletionStage.toCompletableFuture

		val r = f.get
		r.bodyAsString mustBe expectedIP
	}

	"sttp" can {
		import sttp.client3._
		import scala.concurrent.ExecutionContext.Implicits.global
		import sttp.client3.SttpBackendOptions.ProxyType.Socks
		import scala.concurrent.{Await, Future}
		val options = SttpBackendOptions(
			3.seconds,
			Some(SttpBackendOptions.Proxy("localhost", 8888, Socks)))

		val request = basicRequest.get(uri"http://api.ipify.org")
		/*		def checkSync[Identity[_], _](sttpBackend: SttpBackend[Identity, _]): Assertion = {
					val r = sttpBackend.send(request)
					r.body mustBe expectedIP
				}*/

		"simple" in {
			quick.backend
				.send(request).body.right.value mustBe expectedIP
		}

		"HttpClientSync+proxy" in {
			HttpClientSyncBackend(options)
				.send(request).body.right.value mustBe expectedIP
		}

		"HttpURLConnection" in { // OK
			HttpURLConnectionBackend(options)
				.send(request).body.right.value mustBe expectedIP
		}

		def check(backend: SttpBackend[Future, _]) = {
			Await.result(backend
				.send(request).map{ _.body.right.value mustBe expectedIP }
				, 20.seconds)
			import scala.concurrent.{SyncVar, Promise}
		}

/*		"AkkaHttpBackend" in {
			import sttp.client3.akkahttp._
			check(AkkaHttpBackend())
		}*/

		"PekkoHttpBackend" in {
			import sttp.client3.pekkohttp._
			check(PekkoHttpBackend(options))
		}
/*
		"ArmeriaBackend" in {
			import sttp.client3.armeria
			Armeria
			check(ArmeriaWebClient(options))
		}*/

		"OKHttp" must {
			"sync" in { // OK
				import sttp.client3.okhttp.OkHttpSyncBackend
				OkHttpSyncBackend(options)
					.send(request).body.right.value mustBe expectedIP
			}
		}
	}

	"play-ws" in { // OK
		import play.shaded.ahc.org.asynchttpclient.proxy.{ProxyServer, ProxyType}
		import play.shaded.ahc.org.asynchttpclient._
		val asyncHttpClient = Dsl.asyncHttpClient( new DefaultAsyncHttpClientConfig.Builder()
			.setProxyServer(new ProxyServer.Builder("127.0.0.1", 8888).setProxyType(ProxyType.SOCKS_V4))
			.setMaxRequestRetry(0)
			.setShutdownQuietPeriod(0)
			.setShutdownTimeout(0))
		val req = asyncHttpClient.prepareGet("http://api.ipify.org").execute
		/*WsTestClient.withClient{ cli: WSClient =>
			val underlying = cli.underlying.asInstanceOf[AsyncHttpClient]
			underlying.getConfig.
			val req = cli.url("http://api.ipify.org")
				.withProxyServer(DefaultWSProxyServer("127.0.0.1", 8888, Some("socks5")))
		val req = wsClient.url("http://api.ipify.org")*/
		req.get.getResponseBody mustBe expectedIP
		val reqJson = asyncHttpClient.prepareGet("https://api-pub.bitfinex.com/v2/conf/pub:map:currency:label").execute
		import com.github.plokhotnyuk.jsoniter_scala.core._
		import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker.make
		type Result = Seq[Seq[Seq[String]]]
		implicit val resultJsonCodec: JsonValueCodec[Result] = make
		println(readFromStream[Result](reqJson.get.getResponseBodyAsStream))
	}

	"HttpClient" in {
		import java.net.Proxy.Type
		import java.net.http.HttpClient
		import java.net.http.HttpClient._
		import java.net.{ProxySelector, Proxy,InetSocketAddress}
		import scala.collection.mutable.ArrayBuffer
		import java.net.http.HttpResponse.BodyHandlers
		import java.net.http.{ HttpRequest, HttpResponse}
		import java.net.{ SocketAddress, URI}
		import java.util.{List => jList}
		val proxy = new Proxy(Type.SOCKS, new InetSocketAddress("127.0.0.1", 7777) )
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
				println (uri, sa, ioe)
			}
		}
		JDKHttpClientProxySelector.reg(URI.create("http://api.ipify.org"), proxy)
		val client = HttpClient.newBuilder
			.version(Version.HTTP_2)
			.followRedirects(Redirect.NORMAL)
			.proxy(JDKHttpClientProxySelector)
			.build();
		val req = HttpRequest.newBuilder.uri(URI.create("http://api.ipify.org")).build
		client.send( req , BodyHandlers.ofString).body mustBe expectedIP
	}

}