import $ivy.`com.lihaoyi::mill-contrib-playlib:$MILL_VERSION`
import mill._
import scalalib._
import playlib._
import $ivy.`de.tototec::de.tobiasroeser.mill.aspectj::0.5.0`
import de.tobiasroeser.mill.aspectj._
import scala.language.postfixOps

object httpclient extends RootModule with PlayApiModule// with ScalafixModule // No need for _.XXX @ task execution
	//with AspectjModule
	with ScalaModule  {
	def scalaVersion="2.13.14" //"2.12.18" //"3.4.3" //
	val sttpVer = "3.9.8"
	def playVersion = "2.8.22" //"3.0.2"
	val jsoniterVer = "2.30.7"
	val vertxVer = "4.5.8"
	val pekkoVer = "1.0.1"

	val scalaOptions = Seq("-deprecation", "-feature", "-unchecked"
		//, "-Xsource:3-migration", "-explain"
		,"-Xsource:2.13"
		,"-Wconf:cat=scala3-migration:w"
	)
	override
	def scalacOptions = scalaOptions

	import coursier.maven.MavenRepository

	override def repositoriesTask = T.task { super.repositoriesTask() ++ Seq(
		MavenRepository("https://repo.akka.io/maven/")
		,MavenRepository("https://jitpack.io")
	) }
	override def compileIvyDeps = super.compileIvyDeps() ++
		Agg(ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-macros:$jsoniterVer"
		)

	override def ivyDeps = Agg(
		core(),
		server(),
		logback()
		, ivy"com.softwaremill.sttp.client3::core:$sttpVer"
		//, ivy"com.softwaremill.sttp.client3::akka-http-backend:$sttpVer"
		, ivy"com.softwaremill.sttp.client3::pekko-http-backend:$sttpVer"
		, ivy"com.softwaremill.sttp.client3::zio:$sttpVer"
		, ivy"com.softwaremill.sttp.client3::armeria-backend:$sttpVer"
		, ivy"com.softwaremill.sttp.client3::okhttp-backend:$sttpVer"
		, ivy"com.squareup.okio:okio:3.0.0"
		, ivy"org.apache.pekko::pekko-actor-typed:$pekkoVer"
		, ivy"org.apache.pekko::pekko-stream:$pekkoVer"
		, ivy"org.apache.pekko::pekko-http:$pekkoVer"
		, ivy"com.typesafe.akka::akka-http:10.5.3"
		, ivy"com.typesafe.akka::akka-actor-typed:2.8.6"
		, ivy"dev.zio::zio-http:3.0.0"
		, ivy"dev.zio::zio-interop-reactivestreams:2.0.2"
		, ivy"io.vertx:vertx-web-client:$vertxVer"
		, ivy"io.vertx:vertx-reactive-streams:$vertxVer"
		, ivy"io.vertx:vertx-lang-scala_2.12:$vertxVer" // If not found check the pom in $AppData artifactId has to be vertx-scala-lang_2.12
		, ivy"com.github.plokhotnyuk.jsoniter-scala::jsoniter-scala-core:$jsoniterVer"
		, ivy"org.scala-lang.modules::scala-java8-compat:1.0.2"
		, ivy"com.linecorp.armeria::armeria-scala:1.30.1"
		, ivy"org.jboss.resteasy:resteasy-client-vertx:6.2.10.Final"
		, ivy"com.github.cloudonix:vertx-java.io:1.4.0"
		, ivy"org.aspectj:aspectjrt:1.9.20.1"
	)

	/** Add jars from /lib */
	override def unmanagedClasspath = T {
		if (!os.exists(millSourcePath / "lib")) Agg()
		else Agg.from((os.list(millSourcePath / "lib").filter(_.ext == "jar-").map(PathRef(_))))
	}

	def aspectjVersion = "1.9.20"

/*
	override
	def ajcOptions = Seq("-showWeaveInfo","-verbose","-11")
	override
	def aspectIvyDeps = Agg(ivy"com.github.cloudonix:vertx-java.io:1.4.0")
*/

	object test extends PlayTests {
		override
		def scalacOptions = scalaOptions

		override
		def ivyDeps = super.ivyDeps() ++ Agg(
			  //ivy"org.mockito::mockito-scala:1.17.30" // S3: No version !!!
			 ivy"org.scalatest::scalatest:3.2.18"
			, ivy"org.scalatestplus.play::scalatestplus-play:5.1.0" // max 4 scala 2.12 added by the plugin, but needed here for intelliJ
		) ++ httpclient.runIvyDeps() ++ httpclient.compileIvyDeps()  // Needed for IntelliJ import

		override
		def unmanagedClasspath = super.unmanagedClasspath() ++ httpclient.unmanagedClasspath()
	}
}