import net.minecraft.client.input.KeyEvent;
import java.lang.reflect.Method;
public class InspectKeyEvent {
    public static void main(String[] args) {
        for (Method m : KeyEvent.class.getDeclaredMethods()) {
            System.out.println(m.getName() + " -> " + m.getReturnType().getName());
        }
    }
}
