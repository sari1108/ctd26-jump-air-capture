import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

// Generic publish/subscribe message bus. A publisher announces a topic + payload
// without knowing who (if anyone) is listening; a subscriber reacts to a topic
// without knowing who published it. This is what wires the engine to everything
// that reacts to it (score, moves log, sound, animations) without those things
// being baked into the engine itself.
public class Bus {
    private final Map<String, List<Consumer<Object>>> subscribers = new HashMap<>();

    public void subscribe(String topic, Consumer<Object> handler) {
        subscribers.computeIfAbsent(topic, t -> new ArrayList<>()).add(handler);
    }

    public void publish(String topic, Object payload) {
        List<Consumer<Object>> handlers = subscribers.get(topic);
        if (handlers == null) return;
        for (Consumer<Object> handler : handlers) {
            handler.accept(payload);
        }
    }
}
