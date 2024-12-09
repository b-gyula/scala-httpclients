import io.cloudonix.vertx.javaio.WriteToInputStream;
import java.io.IOException;

public aspect WriteInputStreamAspect {
	after(WriteToInputStream wis) : call(* io.cloudonix.vertx.javaio.WriteToInputStream.read(..)) && this(wis){
        try{
        	System.out.println("buffer.size: " + wis.available());
        } catch (IOException e) {
        }
    }
}