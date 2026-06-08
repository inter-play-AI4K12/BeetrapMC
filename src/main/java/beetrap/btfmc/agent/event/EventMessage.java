package beetrap.btfmc.agent.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.Locale;

public class EventMessage extends LinkedHashMap<String, Object> {
    private static final ObjectMapper om;

    static {
        om = new ObjectMapper();
    }

    public EventMessage(String eventType) {
        this.setEventType(eventType);
    }

    public final void setEventType(String eventType) {
        super.put(Key.EVENT_TYPE.toString().toLowerCase(Locale.ROOT),
                eventType.toLowerCase(Locale.ROOT));
    }

    public final String toJsonString() {
        try {
            return om.writeValueAsString(this);
        } catch(JsonProcessingException e) {
            throw new AssertionError(e);
        }
    }

    public enum Key {
        EVENT_TYPE;
    }

    public enum EventTypeValue {
        GAME_START, CHAT, PLAYER_POLLINATE, FLOWER_DEATH, GAME_END
    }
}
