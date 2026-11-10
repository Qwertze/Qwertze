package com.porter.qwerte.printer.render;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.FilterMode;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.block.model.BlockModelPart;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.List;

/**
 * The Minecraft 1.21.11 side of the phantom blocks: blocks are baked with vanilla's own block model renderer into a vertex
 * {@link GpuBuffer} per section (the block vertex format, positions relative to the section) and drawn in one render pass
 * with the translucent moving block pipeline (the one falling blocks and pistons use: block atlas, light map, translucent
 * blend), the quad index buffer being vanilla's shared one. Opacity and tint go in as the color modulator of the
 * transform uniform, so changing them costs no rebake. Only the render thread may call this.
 */
final class PhantomGpu {
    private PhantomGpu() {
    }

    private static final RenderPipeline PIPELINE = RenderPipelines.TRANSLUCENT_MOVING_BLOCK;
    /** USAGE_VERTEX | USAGE_COPY_DST, the usage terrain vertex buffers are created with. */
    private static final int VERTEX_USAGE = GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_COPY_DST;

    /** The baked blocks of one section: a vertex buffer on the GPU. */
    static final class Mesh implements AutoCloseable {
        private final GpuBuffer vertices;
        private final int indexCount;

        private Mesh(GpuBuffer vertices, int indexCount) {
            this.vertices = vertices;
            this.indexCount = indexCount;
        }

        @Override
        public void close() {
            vertices.close();
        }
    }

    /**
     * Changes identity when resources are reloaded (F3+T, a resource pack change): the block model manager bakes new model
     * objects then, so the baked model of stone is a new object. What was baked before holds old texture coordinates.
     */
    static Object reloadToken() {
        return Minecraft.getInstance().getBlockRenderer().getBlockModel(Blocks.STONE.defaultBlockState());
    }

    /** Whether a state is drawn from a block model (chests, beds, signs and the like are drawn by block entity renderers and get no phantom). */
    static boolean hasModel(BlockState state) {
        return state.getRenderShape() == RenderShape.MODEL;
    }

    /** A bake in progress: blocks are added in slices over several frames, then {@link #finish} uploads them. */
    static final class Baker implements AutoCloseable {
        private final BlockAndTintGetter view;
        private final int ox, oy, oz;
        private final ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 16);
        private final BufferBuilder builder = new BufferBuilder(bytes, PIPELINE.getVertexFormatMode(), PIPELINE.getVertexFormat());
        private final PoseStack pose = new PoseStack();
        private final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        private final BlockRenderDispatcher dispatcher = Minecraft.getInstance().getBlockRenderer();
        private final ModelBlockRenderer renderer = dispatcher.getModelRenderer();
        private boolean done;

        /** @param ox the world position the section's vertices are relative to */
        Baker(BlockAndTintGetter view, int ox, int oy, int oz) {
            this.view = view;
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
        }

        /**
         * Bakes one block. Flat shaded (no ambient occlusion: it would read 27 neighbours per face), faces next to another
         * block of the view are left out, light is whatever the view reports.
         */
        void add(BlockState state, int x, int y, int z) {
            if (done || !hasModel(state)) return;
            pos.set(x, y, z);
            RandomSource random = RandomSource.create(state.getSeed(pos));
            List<BlockModelPart> parts = dispatcher.getBlockModel(state).collectParts(random);
            pose.pushPose();
            pose.translate(x - ox, y - oy, z - oz);
            renderer.tesselateWithoutAO(view, parts, state, pos, pose, builder, true, OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        /** Uploads what was added; null when nothing was drawn. */
        Mesh finish() {
            if (done) return null;
            done = true;
            try (MeshData data = builder.build()) {
                if (data == null) return null;
                int indexCount = data.drawState().indexCount();
                GpuBuffer vertices = RenderSystem.getDevice().createBuffer(() -> "Qwerte phantom blocks", VERTEX_USAGE, data.vertexBuffer());
                return new Mesh(vertices, indexCount);
            } finally {
                bytes.close();
            }
        }

        @Override
        public void close() {
            if (!done) {
                done = true;
                bytes.close();
            }
        }
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Drawing, inside Meteor's Render3DEvent

    private static final Matrix4f BASE = new Matrix4f(), WORK = new Matrix4f(), IDENTITY = new Matrix4f();
    private static final Vector4f COLOR = new Vector4f();
    private static final Vector3f NO_OFFSET = new Vector3f();
    private static RenderPass pass;
    private static RenderSystem.AutoStorageIndexBuffer indices;

    /**
     * Opens a render pass on the main target. The model view is the one Meteor's own world rendering uses (camera rotation
     * with the view bobbing taken out and put back by the event's matrices), so a section is drawn by translating by its
     * offset from the camera. The projection is the level's, still bound when the event is posted.
     */
    @SuppressWarnings("deprecation") // TextureAtlas.LOCATION_BLOCKS is the id vanilla still binds the block atlas by
    static void beginDraw(Render3DEvent event) {
        BASE.set(RenderSystem.getModelViewStack()).mul(event.matrices.last().pose());
        Minecraft mc = Minecraft.getInstance();
        RenderTarget target = mc.getMainRenderTarget();
        RenderPass p = RenderSystem.getDevice().createCommandEncoder().createRenderPass(() -> "Qwerte phantom blocks",
            target.getColorTextureView(), OptionalInt.empty(), target.getDepthTextureView(), OptionalDouble.empty());
        try {
            p.setPipeline(PIPELINE);
            RenderSystem.bindDefaultUniforms(p);
            AbstractTexture atlas = mc.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS);
            p.bindTexture("Sampler0", atlas.getTextureView(), atlas.getSampler());
            p.bindTexture("Sampler2", mc.gameRenderer.lightTexture().getTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
            indices = RenderSystem.getSequentialBuffer(VertexFormat.Mode.QUADS);
            pass = p;
        } catch (RuntimeException | Error e) {
            p.close();
            throw e;
        }
    }

    /** Draws one mesh at (dx, dy, dz) from the camera, multiplied by the color. */
    static void draw(Mesh mesh, double dx, double dy, double dz, float r, float g, float b, float a) {
        WORK.set(BASE).translate((float) dx, (float) dy, (float) dz);
        COLOR.set(r, g, b, a);
        pass.setUniform("DynamicTransforms", RenderSystem.getDynamicUniforms().writeTransform(WORK, COLOR, NO_OFFSET, IDENTITY));
        pass.setVertexBuffer(0, mesh.vertices);
        pass.setIndexBuffer(indices.getBuffer(mesh.indexCount), indices.type());
        pass.drawIndexed(0, 0, mesh.indexCount, 1);
    }

    static void endDraw() {
        if (pass != null) pass.close();
        pass = null;
        indices = null;
    }
}
