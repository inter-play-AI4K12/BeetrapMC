package beetrap.btfmc.agent;

import java.util.Arrays;

public record AgentCommand(String commandId, String type, String[] args) {

    @Override
    public String toString() {
        return "AgentCommand{" +
                "commandId='" + this.commandId + '\'' +
                ", " +
                "type='" + this.type + '\'' +
                ", args=" + Arrays.toString(this.args) +
                '}';
    }
}
