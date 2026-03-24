package beetrap.btfmc.state;

import beetrap.btfmc.BeeNestController;
import beetrap.btfmc.GardenInformationBossBar;
import beetrap.btfmc.PlayerInteractionService;
import beetrap.btfmc.flower.Flower;
import beetrap.btfmc.flower.FlowerManager;
import beetrap.btfmc.flower.FlowerPool;
import beetrap.btfmc.flower.FlowerValueScoreboardDisplayerService;
import beetrap.btfmc.networking.NetworkingService;
import beetrap.btfmc.networking.ShowTextScreenS2CPayload;
import beetrap.btfmc.util.AlgorithmOfFloyd;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;

public abstract class BeetrapState implements Iterable<Flower> {

    private static final Logger LOG = LogManager.getLogger(BeetrapState.class);
    protected final BeeNestController beeNestController;
    protected final GardenInformationBossBar gardenInformationBossBar;
    protected final NetworkingService net;
    protected final boolean[] flowers;
    protected ServerWorld world;
    protected BeetrapStateManager stateManager;
    protected FlowerPool flowerPool;
    protected FlowerManager flowerManager;
    protected PlayerInteractionService interaction;
    protected FlowerValueScoreboardDisplayerService flowerValueScoreboardDisplayerService;
    protected boolean usingDiversifyingRankingMethod;
    protected double pollinationCircleRadius;
    protected int amountOfFlowersToWither;
    protected List<Vec3d> pastPollinationLocations;
    private Vec3d beeNestMinecraftPosition;

    /**
     * Create a BeetrapState with a flower pool
     *
     * @param flowerPool the flower pool
     */
    public BeetrapState(ServerWorld world, BeetrapStateManager manager, FlowerPool flowerPool,
            FlowerManager flowerManager, PlayerInteractionService interaction,
            BeeNestController beeNestController, GardenInformationBossBar gardenInformationBossBar,
            FlowerValueScoreboardDisplayerService flowerValueScoreboardDisplayerService,
            boolean usingDiversifyingRankingMethod, double pollinationCircleRadius,
            int amountOfFlowersToWither) {
        this.world = world;
        this.stateManager = manager;
        this.flowerManager = flowerManager;
        this.flowerPool = flowerPool;
        this.interaction = interaction;
        this.beeNestController = beeNestController;
        this.gardenInformationBossBar = gardenInformationBossBar;
        this.flowerValueScoreboardDisplayerService = flowerValueScoreboardDisplayerService;
        this.net = new NetworkingService(this.world);
        this.usingDiversifyingRankingMethod = usingDiversifyingRankingMethod;
        this.pollinationCircleRadius = pollinationCircleRadius;
        this.amountOfFlowersToWither = amountOfFlowersToWither;
        this.flowers = new boolean[this.flowerPool.size()];
        this.pastPollinationLocations = new ArrayList<>();
    }

    protected BeetrapState(BeetrapState state) {
        this.world = state.world;
        this.stateManager = state.stateManager;
        this.flowerPool = new FlowerPool(state.flowerPool);
        this.flowerManager = state.flowerManager;
        this.interaction = state.interaction;
        this.beeNestController = state.beeNestController;
        this.gardenInformationBossBar = state.gardenInformationBossBar;
        this.flowerValueScoreboardDisplayerService = state.flowerValueScoreboardDisplayerService;
        this.net = new NetworkingService(this.world);
        this.usingDiversifyingRankingMethod = state.usingDiversifyingRankingMethod;
        this.pollinationCircleRadius = state.pollinationCircleRadius;
        this.amountOfFlowersToWither = state.amountOfFlowersToWither;
        this.flowers = new boolean[state.flowers.length];
        System.arraycopy(state.flowers, 0, this.flowers, 0, this.flowers.length);
        this.beeNestMinecraftPosition = state.beeNestMinecraftPosition;
        this.pastPollinationLocations = new ArrayList<>(state.pastPollinationLocations);
    }

    protected final void sendMessageToAllPlayers(String message) {
        for(ServerPlayerEntity player : this.world.getPlayers()) {
            player.sendMessage(Text.of(message));
        }
    }

    protected final void showTextScreenToAllPlayers(String message) {
        this.net.broadcastCustomPayload(
                new ShowTextScreenS2CPayload(ShowTextScreenS2CPayload.lineWrap(message, 50)));
    }

    /**
     * Finds flowers within the pollination circle radius that are not already in the garden.
     *
     * @param center   The center of the pollination circle
     * @param radius   The radius of the pollination circle
     * @param maxCount The maximum number of flowers to return
     * @return An array of flowers within the radius that are not in the garden
     */
    protected Flower[] findFlowersWithinRadius(Vec3d center, double radius, int maxCount) {
        double limitedRadius = Math.max(radius - 0.2, 0.0);
        // Find all flowers within the radius sorted by distance to center
        Flower[] flowersWithinRadius = this.findAllFlowersWithinRSortedByG(center, limitedRadius,
                (o1, o2) -> {
                    Vec3d pos1 = flowerManager.getFlowerMinecraftPosition(this, o1);
                    Vec3d pos2 = flowerManager.getFlowerMinecraftPosition(this, o2);
                    double d1 = center.distanceTo(pos1);
                    double d2 = center.distanceTo(pos2);
                    return Double.compare(d1, d2);
                });

        // Filter out flowers that are already in the garden
        List<Flower> candidateFlowers = new ArrayList<>();
        for(Flower flower : flowersWithinRadius) {
            if(!this.hasFlower(flower.getNumber())) {
                candidateFlowers.add(flower);
                if(candidateFlowers.size() >= maxCount) {
                    break;
                }
            }
        }

        return candidateFlowers.toArray(new Flower[0]);
    }

    public abstract void tick();

    public abstract boolean hasNextState();

    public abstract BeetrapState getNextState();

    public abstract boolean timeTravelAvailable();

    public void onPlayerTargetNewEntity(ServerPlayerEntity player, boolean exists, int id) {
        this.flowerValueScoreboardDisplayerService.clearScores();
        Flower flower = this.flowerManager.getFlowerByEntityId(this, id);

        if(flower == null) {
            this.interaction.removeNestFromPlayer(player);
            return;
        }

        if(!this.hasFlower(flower.getNumber()) || flower.hasWithered()) {
            return;
        }

        this.flowerValueScoreboardDisplayerService.displayFlowerValues(this, flower);

        if(exists) {
            this.interaction.giveNestToPlayer(player);
        } else {
            this.interaction.removeNestFromPlayer(player);
        }
    }

    public void onPlayerPollinate(Flower flower, Vec3d flowerMinecraftPosition) {

    }

    public final Vec3d getBeeNestMinecraftPosition() {
        return this.beeNestMinecraftPosition;
    }

    public final void setBeeNestMinecraftPosition(Vec3d position) {
        this.beeNestMinecraftPosition = position;
    }

    public final List<Vec3d> getPastPollinationLocations() {
        return this.pastPollinationLocations;
    }

    public final void populateFlowers(int n) {
        AlgorithmOfFloyd aof = new AlgorithmOfFloyd(this.flowers.length);

        for(int i : aof.sample(n)) {
            this.setFlower(i, true);
        }
    }

    public final boolean hasFlower(int flowerNumber) {
        return this.flowers[flowerNumber];
    }

    public final void setFlower(int flowerNumber, boolean b) {
        this.flowers[flowerNumber] = b;
    }

    public final Flower[] getNFlowersNotInGardenClosestToFSortedByG(Vec3d pos, int n,
            Comparator<Flower> g) {
        Flower[] r = new Flower[n];
        PriorityQueue<Flower> flowers = new PriorityQueue<>(g);
        for(Flower flower : this.flowerPool) {
            if(this.flowers[flower.getNumber()]) {
                continue;
            }

            flowers.add(flower);
        }

        for(int i = 0; i < n; ++i) {
            r[i] = flowers.poll();
        }

        return r;
    }

    public final Flower[] getNFlowersNotInGardenClosestToF(Flower f, int n) {
        return this.getNFlowersNotInGardenClosestToFSortedByG(null, n, (o1, o2) -> {
            double d1 = f.distanceTo(o1);
            double d2 = f.distanceTo(o2);
            return Double.compare(d1, d2);
        });
    }

    public Flower[] findAllFlowersWithinRSortedByG(Vec3d center, double r, Comparator<Flower> g) {
        PriorityQueue<Flower> resultPq = new PriorityQueue<>(g);

        for(Flower h : this.flowerPool) {
            if(center.distanceTo(this.flowerManager.getFlowerMinecraftPosition(this, h)) < r) {
                resultPq.add(h);
            }
        }

        Flower[] resultArray = new Flower[resultPq.size()];

        for(int i = 0, n = resultPq.size(); i < n; ++i) {
            resultArray[i] = resultPq.poll();
        }

        return resultArray;
    }

    public final Flower[] findAtMostNClosestFlowersNotInGardenToCenterByLeastMinecraftDistance(
            Vec3d center, int n) {
        Flower[] f = this.findAllFlowersWithinRSortedByG(center, Double.POSITIVE_INFINITY,
                (o1, o2) -> {
                    Vec3d pos1 = flowerManager.getFlowerMinecraftPosition(BeetrapState.this, o1);
                    Vec3d pos2 = flowerManager.getFlowerMinecraftPosition(BeetrapState.this, o2);
                    double d1 = center.distanceTo(pos1);
                    double d2 = center.distanceTo(pos2);
                    return Double.compare(d1, d2);
                });

        Flower[] g = new Flower[Math.min(f.length, n)];
        int i = 0;
        for(Flower h : f) {
            if(i >= g.length) {
                break;
            }

            if(this.hasFlower(h.getNumber())) {
                continue;
            }

            g[i++] = h;
        }

        return g;
    }

    public final double computeDiversityScore() {
        double s = 0;

        for(Flower f : this) {
            if(f.hasWithered()) {
                continue;
            }

            for(Flower g : this) {
                if(g.hasWithered()) {
                    continue;
                }

                s = s
                        + this.flowerPool.getMappedNormalFlowerPosition(f.getNumber())
                        .getDistance(this.flowerPool.getMappedNormalFlowerPosition(g.getNumber()))
                        * 10;
            }
        }

        return s;
    }

    public final Vec3d computeAveragePastPollinationPositions() {
        int i = this.pastPollinationLocations.size();
        Vec3d v = new Vec3d(0, 0, 0);

        for(Vec3d w : this.pastPollinationLocations) {
            v = v.add(w);
        }

        v = v.multiply(1.0 / i);
        return v;
    }

    @NotNull
    @Override
    public final Iterator<Flower> iterator() {
        return new FlowerIterator();
    }

    public final FlowerPool getFlowerPool() {
        return this.flowerPool;
    }

    @Override
    public String toString() {
        return "BeetrapState{" +
                "world=" + world +
                ", stateManager=" + stateManager +
                ", flowerPool=" + flowerPool +
                ", flowers=" + Arrays.toString(flowers) +
                ", beeNestMinecraftPosition=" + beeNestMinecraftPosition +
                '}';
    }

    public void onMultipleChoiceSelectionResultReceived(String questionId, int option) {

    }

    public void onTextInputScreenResultReceived(String screenId, String textInput) {

    }

    public void onPollinationCircleRadiusIncreaseRequested(double a) {

    }

    public float getGardenInformationBossBarPercent() {
        double v = this.stateManager.getInitialDiversityScore();
        double x = this.computeDiversityScore();
        double w = this.stateManager.getInitialDiversityScore() / 2;

        double a = Math.min(v, w);
        double b = Math.max(v, w);

        return (float)((x - a) / (b - a));
    }

    private final class FlowerIterator implements Iterator<Flower> {

        private int i;

        @Override
        public boolean hasNext() {
            while(true) {
                if(this.i >= BeetrapState.this.flowers.length) {
                    return false;
                }

                if(BeetrapState.this.flowers[this.i]) {
                    return true;
                }

                ++this.i;
            }
        }

        @Override
        public Flower next() {
            while(true) {
                if(this.i >= BeetrapState.this.flowers.length) {
                    return null;
                }

                if(BeetrapState.this.flowers[this.i]) {
                    return BeetrapState.this.flowerPool.getFlowerByNumber(this.i++);
                }

                ++this.i;
            }
        }
    }
}
