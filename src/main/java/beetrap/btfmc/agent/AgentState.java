package beetrap.btfmc.agent;

import net.minecraft.server.network.ServerPlayerEntity;

public abstract class AgentState {

    protected Agent agent;

    public AgentState() {

    }

    public void onAttach() {

    }

    final void onAttach(Agent agent) {
        this.agent = agent;
        this.onAttach();
    }

    public boolean hasNextState() {
        return false;
    }

    public AgentState getNextState() {
        return null;
    }

    public void tick() {

    }

    public void onChatMessageReceived(ServerPlayerEntity serverPlayerEntity, String message) {

    }

    public void onPlayerPollinate() {

    }

    public void onGameStart() {

    }

}
