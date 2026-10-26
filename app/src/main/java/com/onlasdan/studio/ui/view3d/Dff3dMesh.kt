package com.onlasdan.studio.ui.view3d

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.IntBuffer

/**
 * 3D Mesh payload parsed from DFF binary stream via Rust NativeCore.
 */
data class Dff3dMesh(
    val vertexCount: Int,
    val indexCount: Int,
    val bounds: FloatArray, // [minX, minY, minZ, maxX, maxY, maxZ]
    val textures: List<String>,
    val vertexBuffer: FloatBuffer, // Interleaved: pos(3) + normal(3) + uv(2)
    val indexBuffer: IntBuffer,
    val center: FloatArray,
    val radius: Float
) {
    companion object {
        fun fromBytes(bytes: ByteArray): Dff3dMesh? {
            if (bytes.size < 36) return null
            val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val numVerts = bb.int
            val numIndices = bb.int
            val bounds = FloatArray(6) { bb.float }
            val numTextures = bb.int
            val textures = ArrayList<String>(numTextures)
            for (i in 0 until numTextures) {
                val len = bb.int
                val strBytes = ByteArray(len)
                bb.get(strBytes)
                textures.add(String(strBytes, Charsets.UTF_8))
            }

            // Vertices: numVerts * 8 floats
            val vertFloats = numVerts * 8
            val vfb = ByteBuffer.allocateDirect(vertFloats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until vertFloats) {
                vfb.put(bb.float)
            }
            vfb.position(0)

            // Indices: numIndices ints
            val ifb = ByteBuffer.allocateDirect(numIndices * 4).order(ByteOrder.nativeOrder()).asIntBuffer()
            for (i in 0 until numIndices) {
                ifb.put(bb.int)
            }
            ifb.position(0)

            val centerX = (bounds[0] + bounds[3]) * 0.5f
            val centerY = (bounds[1] + bounds[4]) * 0.5f
            val centerZ = (bounds[2] + bounds[5]) * 0.5f
            val dx = bounds[3] - bounds[0]
            val dy = bounds[4] - bounds[1]
            val dz = bounds[5] - bounds[2]
            val radius = maxOf(0.1f, Math.sqrt((dx * dx + dy * dy + dz * dz).toDouble()).toFloat() * 0.5f)

            return Dff3dMesh(
                vertexCount = numVerts,
                indexCount = numIndices,
                bounds = bounds,
                textures = textures,
                vertexBuffer = vfb,
                indexBuffer = ifb,
                center = floatArrayOf(centerX, centerY, centerZ),
                radius = radius
            )
        }
    }
}
