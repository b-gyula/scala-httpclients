import io.cloudonix.vertx.javaio.WriteToInputStream
import org.aspectj.lang.annotation._

@Aspect
class WriteToInputStreamAspect {
	@After("call(int io.cloudonix.vertx.javaio.WriteToInputStream.read(..)) && target(wis)")
	def readCall(wis: WriteToInputStream) = {

		println(wis.available)
    }
}