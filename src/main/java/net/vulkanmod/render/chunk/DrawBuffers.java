package net.vulkanmod.render.chunk;

import net.vulkanmod.render.PipelineManager;
import net.vulkanmod.render.chunk.build.UploadBuffer;
import net.vulkanmod.render.chunk.util.StaticQueue;
import net.vulkanmod.render.vertex.TerrainRenderType;
import net.vulkanmod.vulkan.Renderer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.IndirectBuffer;
import org.joml.Matrix4f;
import org.joml.Vector3i;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class DrawBuffers {

    private static final int VERTEX_SIZE = PipelineManager.TERRAIN_VERTEX_FORMAT.getVertexSize();
    private static final int INDEX_SIZE = Short.BYTES;
    public final short index;
    private final Vector3i origin;
    private final int minHeight;

    private boolean allocated = false;
    AreaBuffer vertexBuffer;
    AreaBuffer indexBuffer;



    public DrawBuffers(int index, Vector3i origin, int minHeight) {

        this.index = (short) index;
        this.origin = origin;
        this.minHeight = minHeight;
    }

    public void allocateBuffers() {
        this.vertexBuffer = new AreaBuffer(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, 3500000, VERTEX_SIZE);
        this.allocated = true;
    }

    public void upload(int xOffset, int yOffset, int zOffset, UploadBuffer buffer, DrawParameters drawParameters) {
        int vertexOffset = drawParameters.vertexOffset;
        int firstIndex = 0;
        drawParameters.baseInstance = encodeSectionOffset(xOffset, yOffset, zOffset);

        if(!buffer.indexOnly) {
            this.vertexBuffer.upload(buffer.getVertexBuffer(), drawParameters.vertexBufferSegment);
//            drawParameters.vertexOffset = drawParameters.vertexBufferSegment.getOffset() / VERTEX_SIZE;
            vertexOffset = drawParameters.vertexBufferSegment.getOffset() / VERTEX_SIZE;

            //debug
//            if(drawParameters.vertexBufferSegment.getOffset() % VERTEX_SIZE != 0) {
//                throw new RuntimeException("misaligned vertex buffer");
//            }
        }

        if(!buffer.autoIndices) {
            if (this.indexBuffer==null)
                this.indexBuffer = new AreaBuffer(VK_BUFFER_USAGE_INDEX_BUFFER_BIT, 1000000, INDEX_SIZE);
            this.indexBuffer.upload(buffer.getIndexBuffer(), drawParameters.indexBufferSegment);
//            drawParameters.firstIndex = drawParameters.indexBufferSegment.getOffset() / INDEX_SIZE;
            firstIndex = drawParameters.indexBufferSegment.getOffset() / INDEX_SIZE;
        }

//        AreaUploadManager.INSTANCE.enqueueParameterUpdate(
//                new ParametersUpdate(drawParameters, buffer.indexCount, firstIndex, vertexOffset));

        drawParameters.indexCount = buffer.indexCount;
        drawParameters.firstIndex = firstIndex;
        drawParameters.vertexOffset = vertexOffset;



        buffer.release();

//        return drawParameters;
    }


    private int encodeSectionOffset(int xOffset, int yOffset, int zOffset) {
        final int xOffset1 = (xOffset & 127);
        final int zOffset1 = (zOffset & 127);
        final int yOffset1 = (yOffset-this.minHeight & 127);
        return yOffset1 << 16 | zOffset1 << 8 | xOffset1;
    }

    private void updateChunkAreaOrigin(double camX, double camY, double camZ, VkCommandBuffer commandBuffer, long layout, FloatBuffer mPtr) {
        
            float x = (float)(camX-(this.origin.x));
            float y = (float)(camY-(this.origin.y));
            float z = (float)(camZ-(this.origin.z));

            Matrix4f MVP = new Matrix4f().set(VRenderSystem.MVP.buffer().asFloatBuffer());
            Matrix4f MV = new Matrix4f().set(VRenderSystem.modelViewMatrix.buffer().asFloatBuffer());

            MVP.translate(-x, -y, -z).get(mPtr);
            MV.translate(-x, -y, -z).get(16,mPtr);

            vkCmdPushConstants(commandBuffer, layout, VK_SHADER_STAGE_VERTEX_BIT, 0, mPtr);
    }

    public void buildDrawBatchesIndirect(IndirectBuffer indirectBuffer, TerrainRenderType terrainRenderType, double camX, double camY, double camZ, long layout, StaticQueue<DrawParameters> typedSectionQueue) {
        int stride = 20;

        int drawCount = 0;

        if(typedSectionQueue==null || typedSectionQueue.size() == 0) return;


        MemoryStack stack = MemoryStack.stackPush();
        ByteBuffer byteBuffer = stack.calloc(20 * typedSectionQueue.size());
        long bufferPtr = MemoryUtil.memAddress0(byteBuffer);


        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        if(isTranslucent) {
            vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0, VK_INDEX_TYPE_UINT16);
        }


        for (var iterator = typedSectionQueue.iterator(isTranslucent); iterator.hasNext(); ) {
            DrawParameters drawParameters = iterator.next();


            //Debug
//            BlockPos o = section.origin;
////            BlockPos pos = new BlockPos(-2188, 65, -1674);
//
////            Vec3 cameraPos = WorldRenderer.getCameraPos();
//            BlockPos pos = new BlockPos(Minecraft.getInstance().getCameraEntity().blockPosition());
//            if(o.getX() <= pos.getX() && o.getY() <= pos.getY() && o.getZ() <= pos.getZ() &&
//                    o.getX() + 16 >= pos.getX() && o.getY() + 16 >= pos.getY() && o.getZ() + 16 >= pos.getZ()) {
//                System.nanoTime();
//
//                }
//
//            }


            //TODO
            if(!drawParameters.ready && drawParameters.vertexBufferSegment.getOffset() != -1) {
                if(!drawParameters.vertexBufferSegment.isReady())
                    continue;
                drawParameters.ready = true;
            }

            long ptr = bufferPtr + (drawCount * 20L);
            MemoryUtil.memPutInt(ptr, drawParameters.indexCount);
            MemoryUtil.memPutInt(ptr + 4, 1);
            MemoryUtil.memPutInt(ptr + 8, drawParameters.firstIndex);
//            MemoryUtil.memPutInt(ptr + 12, drawParameters.vertexBufferSegment.getOffset() / VERTEX_SIZE);
            MemoryUtil.memPutInt(ptr + 12, drawParameters.vertexOffset);
//            MemoryUtil.memPutInt(ptr + 12, drawParameters.vertexBufferSegment.getOffset());
            MemoryUtil.memPutInt(ptr + 16, drawParameters.baseInstance);



            drawCount++;
        }

        if(drawCount == 0) {
            MemoryStack.stackPop();
            return;
        }


        byteBuffer.position(0);

        indirectBuffer.recordCopyCmd(byteBuffer);


        nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0));

//            pipeline.bindDescriptorSets(Drawer.getCommandBuffer(), WorldRenderer.getInstance().getUniformBuffers(), Drawer.getCurrentFrame());
        updateChunkAreaOrigin(camX, camY, camZ, commandBuffer, layout, stack.mallocFloat(32));
        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffer.getId(), indirectBuffer.getOffset(), drawCount, 20);


//            fakeIndirectCmd(Drawer.getCommandBuffer(), indirectBuffer, drawCount, uboBuffer);

//        MemoryUtil.memFree(byteBuffer);
        MemoryStack.stackPop();

    }


    public void buildDrawBatchesDirect(TerrainRenderType terrainRenderType, double camX, double camY, double camZ, long layout, StaticQueue<DrawParameters> typedSectionQueue) {
        if(typedSectionQueue==null || typedSectionQueue.size() == 0) return;
        boolean isTranslucent = terrainRenderType == TerrainRenderType.TRANSLUCENT;

        VkCommandBuffer commandBuffer = Renderer.getCommandBuffer();
        try(MemoryStack stack = MemoryStack.stackPush()) {
            nvkCmdBindVertexBuffers(commandBuffer, 0, 1, stack.npointer(vertexBuffer.getId()), stack.npointer(0));
            updateChunkAreaOrigin(camX, camY, camZ, commandBuffer, layout, stack.mallocFloat(32));
        }


        if(isTranslucent) {
            vkCmdBindIndexBuffer(commandBuffer, this.indexBuffer.getId(), 0, VK_INDEX_TYPE_UINT16);
        }

        for (var iterator = typedSectionQueue.iterator(isTranslucent); iterator.hasNext(); ) {
            final DrawParameters drawParameters = iterator.next();
            vkCmdDrawIndexed(commandBuffer, drawParameters.indexCount, 1, drawParameters.firstIndex, drawParameters.vertexOffset, drawParameters.baseInstance);

        }
    }

    public void releaseBuffers() {
        if(!this.allocated)
            return;

        this.vertexBuffer.freeBuffer();
        if(this.indexBuffer!=null) this.indexBuffer.freeBuffer();

        this.vertexBuffer = null;
        this.indexBuffer = null;
        this.allocated = false;
    }

    public boolean isAllocated() {
        return allocated;
    }

//    public void addDrawCommands(TerrainRenderType r, DrawParameters drawParameters) {
//        this.sectionQueues.get(r).add(drawParameters);
//    }

//    public void clear() {
//        this.sectionQueues.values().forEach(StaticQueue::clear);
//    }

//    public void addRenderTypes(Set<TerrainRenderType> renderTypes) {
//        renderTypes.forEach(renderType ->  this.sectionQueues.computeIfAbsent(renderType, r->new StaticQueue<>(512)));
//    }

//    public void clear(TerrainRenderType r) {
//        this.sectionQueues.get(r).clear();
//    }

    public static class DrawParameters {
        int indexCount, firstIndex, vertexOffset, baseInstance;
        final AreaBuffer.Segment vertexBufferSegment = new AreaBuffer.Segment();
        final AreaBuffer.Segment indexBufferSegment;
        boolean ready = false;

        DrawParameters(boolean translucent) {
            indexBufferSegment = translucent ? new AreaBuffer.Segment() : null;
        }

        public void reset(ChunkArea chunkArea) {
            this.indexCount = 0;
            this.firstIndex = 0;
            this.vertexOffset = 0;

            int segmentOffset = this.vertexBufferSegment.getOffset();
            if(chunkArea != null && chunkArea.drawBuffers().isAllocated() && segmentOffset != -1) {
//                this.chunkArea.drawBuffers.vertexBuffer.setSegmentFree(segmentOffset);
                chunkArea.drawBuffers().vertexBuffer.setSegmentFree(this.vertexBufferSegment);
            }
        }
    }

    public record ParametersUpdate(DrawParameters drawParameters, int indexCount, int firstIndex, int vertexOffset) {

        public void setDrawParameters() {
            this.drawParameters.indexCount = indexCount;
            this.drawParameters.firstIndex = firstIndex;
            this.drawParameters.vertexOffset = vertexOffset;
            this.drawParameters.ready = true;
        }
    }

}
