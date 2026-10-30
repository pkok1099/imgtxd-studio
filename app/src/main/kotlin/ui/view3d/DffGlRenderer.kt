package com.onlasdan.studio.ui.view3d

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * OpenGL ES 2.0 Renderer for RenderWare 3D Models (.dff).
 */
class DffGlRenderer : GLSurfaceView.Renderer {

    var mesh: Dff3dMesh? = null
        set(value) {
            field = value
            needsMeshUpdate = true
        }

    var rotX: Float = -20f
    var rotY: Float = 30f
    var zoomScale: Float = 1.0f
    var panX: Float = 0f
    var panY: Float = 0f
    var wireframe: Boolean = false

    private var needsMeshUpdate = false
    private var programId = 0
    private var aPositionLoc = 0
    private var aNormalLoc = 0
    private var uMvpMatrixLoc = 0
    private var uNormalMatrixLoc = 0
    private var uLightDirLoc = 0
    private var uColorLoc = 0

    private val vMatrix = FloatArray(16)
    private val pMatrix = FloatArray(16)
    private val mMatrix = FloatArray(16)
    private val mvpMatrix = FloatArray(16)
    private val normalMatrix = FloatArray(16)
    private val tempMatrix = FloatArray(16)

    private val vertexShaderCode = """
        uniform mat4 uMVPMatrix;
        uniform mat4 uNormalMatrix;
        uniform vec3 uLightDir;
        attribute vec4 aPosition;
        attribute vec3 aNormal;
        varying vec4 vColor;
        void main() {
            gl_Position = uMVPMatrix * aPosition;
            vec3 transformedNormal = normalize((uNormalMatrix * vec4(aNormal, 0.0)).xyz);
            float diffuse = max(dot(transformedNormal, normalize(uLightDir)), 0.0);
            float ambient = 0.35;
            float light = clamp(ambient + diffuse * 0.65, 0.0, 1.0);
            vColor = vec4(light, light, light, 1.0);
        }
    """.trimIndent()

    private val fragmentShaderCode = """
        precision mediump float;
        varying vec4 vColor;
        uniform vec4 uBaseColor;
        void main() {
            gl_FragColor = vColor * uBaseColor;
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0.12f, 0.12f, 0.14f, 1.0f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glDepthFunc(GLES20.GL_LEQUAL)
        GLES20.glEnable(GLES20.GL_CULL_FACE)
        GLES20.glCullFace(GLES20.GL_BACK)

        val vShader = compileShader(GLES20.GL_VERTEX_SHADER, vertexShaderCode)
        val fShader = compileShader(GLES20.GL_FRAGMENT_SHADER, fragmentShaderCode)
        programId = GLES20.glCreateProgram().also { prog ->
            GLES20.glAttachShader(prog, vShader)
            GLES20.glAttachShader(prog, fShader)
            GLES20.glLinkProgram(prog)
            val linkStatus = IntArray(1)
            GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linkStatus, 0)
            if (linkStatus[0] == 0) {
                GLES20.glDeleteProgram(prog)
            }
        }

        aPositionLoc = GLES20.glGetAttribLocation(programId, "aPosition")
        aNormalLoc = GLES20.glGetAttribLocation(programId, "aNormal")
        uMvpMatrixLoc = GLES20.glGetUniformLocation(programId, "uMVPMatrix")
        uNormalMatrixLoc = GLES20.glGetUniformLocation(programId, "uNormalMatrix")
        uLightDirLoc = GLES20.glGetUniformLocation(programId, "uLightDir")
        uColorLoc = GLES20.glGetUniformLocation(programId, "uBaseColor")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = width.toFloat() / maxOf(1, height).toFloat()
        Matrix.perspectiveM(pMatrix, 0, 45f, aspect, 0.1f, 100f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val m = mesh ?: return

        GLES20.glUseProgram(programId)

        // Camera setup based on model bounding radius
        val dist = (m.radius * 2.8f) / maxOf(0.1f, zoomScale)
        Matrix.setLookAtM(
            vMatrix, 0,
            0f, 0f, dist,
            0f, 0f, 0f,
            0f, 1f, 0f
        )

        // Model matrix: Pan -> Rotate -> Center object at (0, 0, 0)
        Matrix.setIdentityM(mMatrix, 0)
        // Pan
        Matrix.translateM(mMatrix, 0, panX * (m.radius * 0.005f), -panY * (m.radius * 0.005f), 0f)
        // Orbit rotation
        Matrix.rotateM(mMatrix, 0, rotX, 1f, 0f, 0f)
        Matrix.rotateM(mMatrix, 0, rotY, 0f, 1f, 0f)
        // RenderWare coordinates typically have Z up. Rotate so Z is up/Y is forward:
        Matrix.rotateM(mMatrix, 0, -90f, 1f, 0f, 0f)
        // Recenter geometry bounding box at origin
        Matrix.translateM(mMatrix, 0, -m.center[0], -m.center[1], -m.center[2])

        // MVP Matrix = Projection * View * Model
        Matrix.multiplyMM(tempMatrix, 0, vMatrix, 0, mMatrix, 0)
        Matrix.multiplyMM(mvpMatrix, 0, pMatrix, 0, tempMatrix, 0)

        // Normal Matrix (invert & transpose of model matrix)
        Matrix.invertM(tempMatrix, 0, mMatrix, 0)
        Matrix.transposeM(normalMatrix, 0, tempMatrix, 0)

        GLES20.glUniformMatrix4fv(uMvpMatrixLoc, 1, false, mvpMatrix, 0)
        GLES20.glUniformMatrix4fv(uNormalMatrixLoc, 1, false, normalMatrix, 0)
        // Light direction from upper right front
        GLES20.glUniform3f(uLightDirLoc, 0.5f, 0.8f, 1.0f)

        // Vertex buffer stride: 8 floats * 4 bytes = 32 bytes
        val stride = 8 * 4
        m.vertexBuffer.position(0)
        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, stride, m.vertexBuffer)

        m.vertexBuffer.position(3)
        GLES20.glEnableVertexAttribArray(aNormalLoc)
        GLES20.glVertexAttribPointer(aNormalLoc, 3, GLES20.GL_FLOAT, false, stride, m.vertexBuffer)

        m.indexBuffer.position(0)

        if (wireframe) {
            // Draw wireframe in light cyan
            GLES20.glUniform4f(uColorLoc, 0.3f, 0.9f, 1.0f, 1.0f)
            GLES20.glDisable(GLES20.GL_CULL_FACE)
            GLES20.glDrawElements(GLES20.GL_LINES, m.indexCount, GLES20.GL_UNSIGNED_INT, m.indexBuffer)
        } else {
            // Solid shaded render with steel-blue tone
            GLES20.glUniform4f(uColorLoc, 0.78f, 0.82f, 0.88f, 1.0f)
            GLES20.glDisable(GLES20.GL_CULL_FACE) // Many DFFs are double-sided or open meshes
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, m.indexCount, GLES20.GL_UNSIGNED_INT, m.indexBuffer)
        }

        GLES20.glDisableVertexAttribArray(aPositionLoc)
        GLES20.glDisableVertexAttribArray(aNormalLoc)
    }

    private fun compileShader(type: Int, shaderCode: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, shaderCode)
        GLES20.glCompileShader(shader)
        val compileStatus = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compileStatus, 0)
        return shader
    }
}
