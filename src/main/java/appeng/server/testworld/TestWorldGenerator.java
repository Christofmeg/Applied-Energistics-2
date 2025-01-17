package appeng.server.testworld;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.google.common.collect.Iterables;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;

import appeng.core.definitions.AEBlocks;
import appeng.server.testplots.TestPlots;

public class TestWorldGenerator {
    /**
     * Padding around plots.
     */
    private static final int PADDING = 3;
    private static final int OUTER_PADDING = 10;
    private final ServerLevel level;
    private final BlockPos origin;
    private final ServerPlayer player;
    private final List<PositionedPlot> positionedPlots;
    private final BoundingBox overallBounds;
    private final BlockPos suitableStartPos;

    public TestWorldGenerator(ServerLevel level, ServerPlayer player, BlockPos origin,
            @Nullable ResourceLocation plotId) {
        this.level = level;
        this.origin = origin;
        this.player = player;

        // Try to position the plots
        List<Plot> plots;
        if (plotId != null) {
            plots = Collections.singletonList(TestPlots.getById(plotId));
        } else {
            plots = TestPlots.createPlots();
        }

        var positionedArea = RectanglePacking.pack(plots, plot -> {
            var bb = plot.getBounds();
            return new RectanglePacking.Size(bb.getXSpan() + 2 * PADDING, bb.getZSpan() + 2 * PADDING);
        });

        // Compute the overall bounding box containing all positioned plots
        positionedPlots = positionedArea.rectangles().stream().map(pp -> {
            // Remember we added padding to the overall area of each plot for placement
            var relativeBounds = pp.what().getBounds();
            var plotOrigin = new BlockPos(pp.x() - pp.what().getBounds().minX() + PADDING,
                    origin.getY(),
                    pp.y() - pp.what().getBounds().minZ() + PADDING);
            var absBoundingBox = relativeBounds.moved(
                    plotOrigin.getX(),
                    plotOrigin.getY(),
                    plotOrigin.getZ());

            return new PositionedPlot(plotOrigin, absBoundingBox, pp.what());
        }).toList();
        overallBounds = BoundingBox.encapsulatingBoxes(
                positionedPlots.stream().map(PositionedPlot::bounds).toList()).orElseThrow();

        suitableStartPos = origin.offset(positionedArea.w() / 2, 0, -2);
    }

    public BlockPos getSuitableStartPos() {
        return suitableStartPos;
    }

    public boolean isWithinBounds(BlockPos pos) {
        return overallBounds
                .inflatedBy(10)
                .isInside(pos);
    }

    public void generate() {
        clearLevel();
        buildPlatform();
        var entities = new ArrayList<Entity>();
        buildPlots(entities);

        clearEntities(entities);
    }

    private void buildPlots(List<Entity> entities) {
        for (var positionedPlot : positionedPlots) {
            // Outline the plot
            outline(positionedPlot);

            // Place a sign with the plot id
            placeSign(positionedPlot);

            positionedPlot.plot.build(level, player, positionedPlot.origin, entities);
        }
    }

    private void placeSign(PositionedPlot positionedPlot) {
        var signPos = new BlockPos(
                positionedPlot.bounds.maxX() + 2,
                origin.getY(),
                positionedPlot.bounds.minZ() - 2);
        level.setBlock(signPos,
                Blocks.OAK_SIGN.defaultBlockState().rotate(Rotation.CLOCKWISE_180),
                Block.UPDATE_ALL);
        level.getBlockEntity(signPos, BlockEntityType.SIGN).ifPresent(sign -> {
            var signText = sign.getText(true);
            sign.setAllowedPlayerEditor(null);
            signText.setHasGlowingText(true);
            signText.setColor(DyeColor.WHITE);

            var text = new StringBuilder(positionedPlot.plot.getId().getPath());
            int line = 0;
            while (line < SignText.LINES && !text.isEmpty()) {
                var lineLength = Math.min(12, text.length()); // Sign lines should fit roughly 12 chars
                var lineText = text.substring(0, lineLength);
                text.delete(0, lineLength);

                signText = signText.setMessage(line++, Component.literal(lineText));
            }

            sign.setText(signText, true);
        });
    }

    /**
     * Change the floor beneath a plot to small bricks to more easily see the plot outline
     */
    private void outline(PositionedPlot positionedPlot) {
        var from = new BlockPos(
                positionedPlot.bounds.minX() - 1,
                positionedPlot.origin.getY() - 1,
                positionedPlot.bounds.minZ() - 1);
        var to = new BlockPos(
                positionedPlot.bounds.maxX() + 1,
                positionedPlot.origin.getY() - 1,
                positionedPlot.bounds.maxZ() + 1);
        for (var pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, AEBlocks.SKY_STONE_SMALL_BRICK.block().defaultBlockState(), Block.UPDATE_ALL);
        }
    }

    private void buildPlatform() {
        var state = AEBlocks.SKY_STONE_BRICK.block().defaultBlockState();
        var from = new BlockPos(overallBounds.minX() - OUTER_PADDING, origin.getY() - 3,
                overallBounds.minZ() - OUTER_PADDING);
        var to = new BlockPos(overallBounds.maxX() + OUTER_PADDING, origin.getY() - 1,
                overallBounds.maxZ() + OUTER_PADDING);
        for (var pos : BlockPos.betweenClosed(from, to)) {
            level.setBlock(pos, state, Block.UPDATE_ALL);
        }
    }

    private void clearLevel() {
        var from = new ChunkPos(
                new BlockPos(overallBounds.minX() - OUTER_PADDING, 0, overallBounds.minZ() - OUTER_PADDING));
        var to = new ChunkPos(
                new BlockPos(overallBounds.maxX() + OUTER_PADDING, 0, overallBounds.maxZ() + OUTER_PADDING));

        ChunkPos.rangeClosed(from, to).forEach(chunkPos -> {
            var chunk = level.getChunk(chunkPos.x, chunkPos.z);
            if (!chunk.isEmpty()) {
                clearChunk(chunk);
            }
        });
    }

    private void clearChunk(LevelChunk chunk) {
        if (chunk.isEmpty()) {
            return;
        }

        int sectionId = 0;
        for (var sec : chunk.getSections()) {
            if (!sec.hasOnlyAir()) {
                var p = new BlockPos.MutableBlockPos();
                var air = Blocks.AIR.defaultBlockState();
                int bottomBlock = chunk.getMinBuildHeight() + SectionPos.SECTION_SIZE * sectionId;
                for (var y = 0; y < SectionPos.SECTION_SIZE; y++) {
                    p.setY(bottomBlock + y);
                    for (var x = 0; x < 16; x++) {
                        p.setX(chunk.getPos().getMinBlockX() + x);
                        for (var z = 0; z < 16; z++) {
                            p.setZ(chunk.getPos().getMinBlockZ() + z);
                            level.setBlock(p, air, Block.UPDATE_ALL);
                        }
                    }
                }
            }
            sectionId++;
        }
    }

    private void clearEntities(List<Entity> plotEntities) {
        // Clear up any item entities that might have spawned
        var entities = Iterables.toArray(level.getAllEntities(), Entity.class);
        for (var entity : entities) {
            if (!plotEntities.contains(entity) && !(entity instanceof Player) && entity.isAlive()) {
                entity.discard();
            }
        }
    }

    private record PositionedPlot(
            BlockPos origin,
            BoundingBox bounds,
            Plot plot) {
    }
}
