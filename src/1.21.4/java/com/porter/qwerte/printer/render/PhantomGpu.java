package com.porter.qwerte.printer.render;

import com.mojang.blaze3d.buffers.BufferUsage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.renderer.block.ModelBlockRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import org.joml.Matrix4f;

/**
 * The Minecraft 1.21.4 side of the phantom blocks: blocks are baked with vanilla's own block model renderer into a
 * {@link VertexBuffer} per section (the format of terrain, positions relative to the section), and drawn with the
 * translucent terrain render type, with the opacity and tint going in as the shader color (the translucent shader multiplies
 * by it), so changing them costs no rebake. Only the render thread may call this.
 */
final class PhantomGpu {
    private PhantomGpu() {
    }

    /** The baked blocks of one section: a vertex buffer on the GPU. */
    static final class Mesh implements AutoCloseable {
        private final VertexBuffer buffer;

        private Mesh(VertexBuffer buffer) {
            this.buffer = buffer;
        }

        @Override
        public void close() {
            buffer.close();
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
        private final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        private final PoseStack pose = new PoseStack();
        private final RandomSource random = RandomSource.create();
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
            BakedModel model = dispatcher.getBlockModel(state);
            pose.pushPose();
            pose.translate(x - ox, y - oy, z - oz);
            renderer.tesselateWithoutAO(view, model, state, pos, pose, builder, true, random, state.getSeed(pos), OverlayTexture.NO_OVERLAY);
            pose.popPose();
        }

        /** Uploads what was added; null when nothing was drawn. */
        Mesh finish() {
            if (done) return null;
            done = true;
            try {
                MeshData data = builder.build();
                if (data == null) return null;
                VertexBuffer vb = new VertexBuffer(BufferUsage.STATIC_WRITE);
                try {
                    vb.bind();
                    vb.upload(data);
                    VertexBuffer.unbind();
                    return new Mesh(vb);
                } catch (RuntimeException | Error e) {
                    vb.close();
                    try {
                        data.close();
                    } catch (RuntimeException ignored) {
                        // upload had already closed it
                    }
                    throw e;
                }
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

    private static final Matrix4f BASE = new Matrix4f(), WORK = new Matrix4f();
    private static RenderType type;
    private static CompiledShaderProgram shader;

    /**
     * Sets up the translucent terrain state. The model view is the one Meteor's own world rendering uses (camera rotation
     * with the view bobbing taken out and put back by the event's matrices), so a section is drawn by translating by its
     * offset from the camera.
     */
    static void beginDraw(Render3DEvent event) {
        BASE.set(RenderSystem.getModelViewStack()).mul(event.matrices.last().pose());
        type = RenderType.translucent();
        type.setupRenderState();
        shader = RenderSystem.getShader();
    }

    /** Draws one mesh at (dx, dy, dz) from the camera, multiplied by the color. */
    static void draw(Mesh mesh, double dx, double dy, double dz, float r, float g, float b, float a) {
        WORK.set(BASE).translate((float) dx, (float) dy, (float) dz);
        RenderSystem.setShaderColor(r, g, b, a);
        mesh.buffer.bind();
        mesh.buffer.drawWithShader(WORK, RenderSystem.getProjectionMatrix(), shader);
    }

    static void endDraw() {
        VertexBuffer.unbind();
        if (type != null) type.clearRenderState();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        type = null;
        shader = null;
    }
}
