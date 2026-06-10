package beetrap.btfmc.agent.event;

import java.util.Map;

/**
 * Periodic world and execution snapshot sent to BeeCuriousService.
 */
public class AgentTickEventMessage extends EventMessage {

    public AgentTickEventMessage(Map<String, Object> snapshot,
            Map<String, Object> execution) {
        super("agent_tick");
        super.put("snapshot", snapshot);
        super.put("execution", execution);
    }
}
