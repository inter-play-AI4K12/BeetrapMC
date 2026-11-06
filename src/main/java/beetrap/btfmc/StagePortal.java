package beetrap.btfmc;

import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.NetherPortalBlock;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * A nether portal frame built a few blocks in front of the player when a tutorial stage ends. It
 * never actually takes anyone to the Nether: walking into it is detected right away (vanilla needs
 * several seconds inside a portal to teleport a non-creative player) and moves the player to the
 * next stage instead. Every block it replaces is remembered and put back when it's removed.
 */
public final class StagePortal {

    private static final int DISTANCE = 4;   // blocks in front of the player

    private final ServerWorld world;
    private final Map<BlockPos, BlockState> replaced = new LinkedHashMap<>();
    private final Box inside;
    private final Vec3d center;

    private StagePortal(ServerWorld world, Box inside, Vec3d center) {
        this.world = world;
        this.inside = inside;
        this.center = center;
    }

    /** Build a 4x5 obsidian frame with a 2x3 portal, facing the player, {@link #DISTANCE} ahead. */
    public static StagePortal build(ServerWorld world, ServerPlayerEntity player) {
        Direction facing = player.getHorizontalFacing();
        Direction along = facing.rotateYClockwise();   // the frame runs sideways to the player
        BlockPos feet = player.getBlockPos();
        BlockPos base = feet.offset(facing, DISTANCE).offset(along.getOpposite(), 1);
        Direction.Axis axis = along.getAxis();

        BlockPos insideMin = base.offset(along, 1);
        BlockPos insideMax = base.offset(along, 2).up(2);
        Box inside = new Box(Vec3d.of(insideMin), Vec3d.of(insideMax).add(1, 1, 1)).expand(0.2);
        Vec3d center = Vec3d.ofCenter(insideMin).add(Vec3d.ofCenter(insideMax)).multiply(0.5);
        StagePortal portal = new StagePortal(world, inside, center);

        // Frame first (bottom row sits in the ground, top row 4 above the player's feet)...
        for(int i = 0; i < 4; ++i) {
            portal.set(base.offset(along, i).down(), Blocks.OBSIDIAN.getDefaultState());
            portal.set(base.offset(along, i).up(3), Blocks.OBSIDIAN.getDefaultState());
        }
        for(int dy = 0; dy < 3; ++dy) {
            portal.set(base.up(dy), Blocks.OBSIDIAN.getDefaultState());
            portal.set(base.offset(along, 3).up(dy), Blocks.OBSIDIAN.getDefaultState());
        }
        // ...then the purple portal itself, placed without neighbour updates so it holds.
        BlockState portalBlock = Blocks.NETHER_PORTAL.getDefaultState()
                .with(NetherPortalBlock.AXIS, axis);
        for(int i = 1; i <= 2; ++i) {
            for(int dy = 0; dy < 3; ++dy) {
                portal.set(base.offset(along, i).up(dy), portalBlock);
            }
        }
        return portal;
    }

    private void set(BlockPos pos, BlockState state) {
        BlockPos key = pos.toImmutable();
        this.replaced.putIfAbsent(key, this.world.getBlockState(key));
        this.world.setBlockState(key, state, Block.NOTIFY_LISTENERS | Block.FORCE_STATE);
    }

    public Vec3d center() {
        return this.center;
    }

    public boolean contains(ServerPlayerEntity player) {
        return player.getBoundingBox().intersects(this.inside);
    }

    /** Put back everything the portal replaced (portal blocks first, so nothing pops). */
    public void remove() {
        this.replaced.entrySet().stream()
                .sorted((a, b) -> Boolean.compare(
                        !a.getValue().isOf(Blocks.AIR), !b.getValue().isOf(Blocks.AIR)))
                .forEach(e -> this.world.setBlockState(e.getKey(), e.getValue(),
                        Block.NOTIFY_LISTENERS | Block.FORCE_STATE));
        this.replaced.clear();
    }
}
