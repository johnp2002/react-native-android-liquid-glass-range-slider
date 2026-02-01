package com.reactnativeandroidliquidglassrangeslider

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.opengl.GLES30
import android.opengl.GLUtils
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.PixelCopy
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import com.facebook.react.uimanager.ThemedReactContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGL10
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.egl.EGLContext
import javax.microedition.khronos.egl.EGLDisplay
import javax.microedition.khronos.egl.EGLSurface

class LiquidGlassRangeSliderView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {

    private var renderer: LiquidGlassRenderer? = null
    private val handler = Handler(Looper.getMainLooper())

    var refraction: Float = 0.5f
        set(value) {
            field = value
            renderer?.setRefraction(value)
        }

    var magnification: Float = 1.0f
        set(value) {
            field = value
            renderer?.setMagnification(value)
        }

    var offsetX: Float = 0f
        set(value) {
            field = value
            renderer?.setOffsetX(value)
        }

    var offsetY: Float = 0f
        set(value) {
            field = value
            renderer?.setOffsetY(value)
        }

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    private val frameCallback = object : android.view.Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (renderer != null) {
                val location = IntArray(2)
                getLocationOnScreen(location)
                
                // Add fractional translation to restore sub-pixel precision
                // getLocationInWindow returns integer floor/round positions.
                // We add the fractional part of the current translation.
                val tx = translationX
                val ty = translationY
                val fracX = tx - tx.toInt()
                val fracY = ty - ty.toInt()
                
                renderer?.updateViewPosition(location[0].toFloat() + fracX, location[1].toFloat() + fracY)
                android.view.Choreographer.getInstance().postFrameCallback(this)
            }
        }
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        renderer = LiquidGlassRenderer(surface, width, height)
        renderer?.setRefraction(refraction)
        renderer?.setMagnification(magnification)
        renderer?.setOffsetX(offsetX)
        renderer?.setOffsetY(offsetY)
        renderer?.startRendering()
        android.view.Choreographer.getInstance().postFrameCallback(frameCallback)
        captureLoop()
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        renderer?.updateSize(width, height)
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        renderer?.stopRendering()
        renderer = null
        android.view.Choreographer.getInstance().removeFrameCallback(frameCallback)
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {}
    
    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == View.VISIBLE) {
            lastCaptureRect = null // Force fresh capture on re-appear
        }
    }

    private fun captureLoop() {
        if (renderer == null) return
        captureBackground()
        handler.postDelayed({ captureLoop() }, 30) // 30ms capture
    }

    private var lastCaptureRect: Rect? = null

    private fun captureBackground() {
        try {
            val activity = (context as? ThemedReactContext)?.currentActivity ?: return
            val window = activity.window ?: return
            val rootView = window.decorView

            val location = IntArray(2)
            getLocationOnScreen(location)
            
            val viewX = location[0]
            val viewY = location[1]
            val w = width
            val h = height

            // Hysteresis Logic:
            // Define the "Desired" capture area (View + Padding)
            val pad = 40
            
            // Check if existing rect is still valid and covers the view with safe margin
            val existing = lastCaptureRect
            var useExisting = false
            
            if (existing != null) {
                // margin: at least 20px buffer
                val safeMargin = 20
                if (viewX >= existing.left + safeMargin &&
                    viewY >= existing.top + safeMargin &&
                    (viewX + w) <= existing.right - safeMargin &&
                    (viewY + h) <= existing.bottom - safeMargin) {
                    useExisting = true
                }
            }
            
            val captureRect: Rect
            
            if (useExisting && existing != null) {
                captureRect = existing
            } else {
                val newPad = 40 
                val cX = (viewX - newPad).coerceAtLeast(0)
                val cY = (viewY - newPad).coerceAtLeast(0)
                val cW = (w + newPad * 2).coerceAtMost(rootView.width - cX)
                val cH = (h + newPad * 2).coerceAtMost(rootView.height - cY)
                
                captureRect = Rect(cX, cY, cX + cW, cY + cH)
                
                if (captureRect.width() <= 0 || captureRect.height() <= 0) return
                lastCaptureRect = captureRect
            }

            // Draw to Bitmap synchronization (Eliminate Feedback Loop)
            val bitmap = Bitmap.createBitmap(captureRect.width(), captureRect.height(), Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            
            // 1. Hide Thumb to prevent self-capture (Feedback Loop)
            this.visibility = View.INVISIBLE
            
            // 2. Move Canvas to capture region relative to Root
            canvas.translate(-captureRect.left.toFloat(), -captureRect.top.toFloat())
            
            // 3. Draw Root (Background)
            rootView.draw(canvas)
            
            // 4. Show Thumb
            this.visibility = View.VISIBLE
            
            // 5. Upload
            renderer?.uploadScene(bitmap, captureRect.left, captureRect.top)
            
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private class LiquidGlassRenderer(
        private val surface: SurfaceTexture,
        private var width: Int,
        private var height: Int
    ) : Thread() {
        private var running = false
        private var egl: EGL10? = null
        private var eglDisplay: EGLDisplay? = null
        private var eglContext: EGLContext? = null
        private var eglSurface: EGLSurface? = null
        
        private var sceneTextureId = 0
        private var pendingSceneBitmap: Bitmap? = null
        
        private var pendingCaptureX = 0
        private var pendingCaptureY = 0
        private var currentCaptureX = 0
        private var currentCaptureY = 0
        private var currentCaptureW = 0
        private var currentCaptureH = 0
        
        @Volatile private var viewScreenX = 0f
        @Volatile private var viewScreenY = 0f

        @Volatile private var uRefraction = 0.5f
        @Volatile private var uMagnification = 1.0f
        @Volatile private var uOffsetX = 0f
        @Volatile private var uOffsetY = 0f

        private var glassProgram = 0
        private var vbo = 0

        fun startRendering() {
            running = true
            start()
        }

        fun stopRendering() {
            running = false
            try { join() } catch (e: InterruptedException) {}
        }

        fun updateSize(w: Int, h: Int) {
            width = w
            height = h
        }

        fun updateViewPosition(x: Float, y: Float) {
            viewScreenX = x
            viewScreenY = y
        }

        fun setRefraction(v: Float) { uRefraction = v }
        fun setMagnification(v: Float) { uMagnification = v }
        fun setOffsetX(v: Float) { uOffsetX = v }
        fun setOffsetY(v: Float) { uOffsetY = v }

        fun uploadScene(bitmap: Bitmap, x: Int, y: Int) {
            pendingSceneBitmap = bitmap
            pendingCaptureX = x
            pendingCaptureY = y
        }

        override fun run() {
            initGL()
            initResources()
            while (running) {
                renderFrame()
                try { sleep(16) } catch (e: InterruptedException) {}
            }
            shutdownGL()
        }

        private fun initGL() {
            egl = EGLContext.getEGL() as EGL10
            eglDisplay = egl!!.eglGetDisplay(EGL10.EGL_DEFAULT_DISPLAY)
            egl!!.eglInitialize(eglDisplay, IntArray(2))
            val attribList = intArrayOf(
                EGL10.EGL_RED_SIZE, 8, EGL10.EGL_GREEN_SIZE, 8, EGL10.EGL_BLUE_SIZE, 8, EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_RENDERABLE_TYPE, 64, 
                0x3024, 8, 0x3023, 8, 0x3022, 8, 0x3021, 8, EGL10.EGL_NONE
            )
            val configs = arrayOfNulls<EGLConfig>(1)
            val numConfigs = IntArray(1)
            egl!!.eglChooseConfig(eglDisplay, attribList, configs, 1, numConfigs)
            val contextAttribs = intArrayOf(0x3098, 3, EGL10.EGL_NONE)
            eglContext = egl!!.eglCreateContext(eglDisplay, configs[0], EGL10.EGL_NO_CONTEXT, contextAttribs)
            eglSurface = egl!!.eglCreateWindowSurface(eglDisplay, configs[0], surface, null)
            egl!!.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
            
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }

        private fun initResources() {
            val vertices = floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)
            val vboIds = IntArray(1)
            GLES30.glGenBuffers(1, vboIds, 0)
            vbo = vboIds[0]
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            val buf = ByteBuffer.allocateDirect(vertices.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buf.put(vertices).position(0)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size*4, buf, GLES30.GL_STATIC_DRAW)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, 0)

            glassProgram = createProgram(VERT_SHADER, FRAG_SHADER_USER_PORT)
        }

        private fun renderFrame() {
            val bmp = pendingSceneBitmap
            if (bmp != null) {
                if (sceneTextureId == 0) {
                    val ids = IntArray(1); GLES30.glGenTextures(1, ids, 0); sceneTextureId = ids[0]
                    GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
                    GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
                }
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
                GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, bmp, 0)
                currentCaptureX = pendingCaptureX
                currentCaptureY = pendingCaptureY
                currentCaptureW = bmp.width
                currentCaptureH = bmp.height
                pendingSceneBitmap = null
            }

            if (sceneTextureId == 0) {
                 GLES30.glClearColor(0f,0f,0f,0f); GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                 egl!!.eglSwapBuffers(eglDisplay, eglSurface)
                 return
            }

            GLES30.glViewport(0, 0, width, height)
            GLES30.glClearColor(0f, 0f, 0f, 0f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            
            GLES30.glUseProgram(glassProgram)
            val aPos = GLES30.glGetAttribLocation(glassProgram, "aPos")
            val aUV = GLES30.glGetAttribLocation(glassProgram, "aUV")
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, 0)
            GLES30.glEnableVertexAttribArray(aPos)
            GLES30.glVertexAttribPointer(aUV, 2, GLES30.GL_FLOAT, false, 16, 8)
            GLES30.glEnableVertexAttribArray(aUV)

            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, sceneTextureId)
            GLES30.glUniform1i(GLES30.glGetUniformLocation(glassProgram, "screen_texture"), 0)

            GLES30.glUniform2f(GLES30.glGetUniformLocation(glassProgram, "uResolution"), width.toFloat(), height.toFloat())
            GLES30.glUniform2f(GLES30.glGetUniformLocation(glassProgram, "uViewPos"), viewScreenX.toFloat(), viewScreenY.toFloat())
            GLES30.glUniform4f(GLES30.glGetUniformLocation(glassProgram, "uCaptureRect"), 
                currentCaptureX.toFloat(), currentCaptureY.toFloat(), currentCaptureW.toFloat(), currentCaptureH.toFloat())
                
            // User Parameters
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_roundness"), 1.0f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_distortion_intensity"), 0.1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_blur_intensity"), 0.1f)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_refraction"), uRefraction)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_magnification"), uMagnification)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_offset_x"), uOffsetX)
            GLES30.glUniform1f(GLES30.glGetUniformLocation(glassProgram, "u_offset_y"), uOffsetY)

            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            egl!!.eglSwapBuffers(eglDisplay, eglSurface)
        }

        private fun createProgram(v: String, f: String): Int {
            val p = GLES30.glCreateProgram()
            val vs = loadShader(GLES30.GL_VERTEX_SHADER, v)
            val fs = loadShader(GLES30.GL_FRAGMENT_SHADER, f)
            GLES30.glAttachShader(p, vs)
            GLES30.glAttachShader(p, fs)
            GLES30.glLinkProgram(p)
            return p
        }

        private fun loadShader(t: Int, s: String): Int {
            val h = GLES30.glCreateShader(t)
            GLES30.glShaderSource(h, s)
            GLES30.glCompileShader(h)
            val c = IntArray(1)
            GLES30.glGetShaderiv(h, GLES30.GL_COMPILE_STATUS, c, 0)
            if (c[0] == 0) Log.e("LGV", "Shader Log: " + GLES30.glGetShaderInfoLog(h))
            return h
        }
        
        private fun shutdownGL() {
            egl!!.eglMakeCurrent(eglDisplay, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
            egl!!.eglDestroySurface(eglDisplay, eglSurface)
            egl!!.eglDestroyContext(eglDisplay, eglContext)
            egl!!.eglTerminate(eglDisplay)
        }
    }

    companion object {
        const val VERT_SHADER = """#version 300 es
            layout(location=0) in vec2 aPos;
            layout(location=1) in vec2 aUV;
            out vec2 vUV;
            void main(){ vUV = aUV; gl_Position = vec4(aPos,0.0,1.0); }
        """

        const val FRAG_SHADER_USER_PORT = """#version 300 es
            precision highp float;
            out vec4 FragColor;
            in vec2 vUV; // Matches 'UV' in Godot (0..1)
            
            uniform sampler2D screen_texture;
            
            uniform vec2 uResolution; // View Size (Width, Height)
            uniform vec2 uViewPos;    // View Pos on Screen
            uniform vec4 uCaptureRect;// Capture Rect (x,y,w,h)
            
            // User Uniforms
            uniform float u_roundness;
            uniform float u_distortion_intensity;
            uniform float u_blur_intensity;
            uniform float u_refraction;
            uniform float u_magnification;
            uniform float u_offset_x;
            uniform float u_offset_y;

            float sdf_box(vec2 p, vec2 b, float r) {
                vec2 d = abs(p) - b + vec2(r);
                return min(max(d.x, d.y), 0.0) + length(max(d, 0.0)) - r;
            }

            vec3 get_blurred_color(sampler2D tex, vec2 screen_uv, vec2 pixel_size, float blur_radius) {
                vec3 color = vec3(0.0);
                float total_weight = 0.0;
                
                // 5x5 Kernel Loop
                for (int x = -2; x <= 2; x++) {
                    for (int y = -2; y <= 2; y++) {
                        vec2 offset = vec2(float(x), float(y)) * blur_radius * pixel_size;
                        
                        // Gaussian weight
                        float weight = exp(-0.5 * (float(x*x + y*y)) / 2.0);
                        
                        // We clamp the UV here because 'screen_uv' is local to our captured texture.
                        // Actually, GL_CLAMP_TO_EDGE handles this, but sampling strictly outside might yield edge pixels.
                        color += texture(tex, screen_uv + offset).rgb * weight;
                        total_weight += weight;
                    }
                }
                return color / total_weight;
            }

            void main() {
                // Settings from snippet
                float CircleRadius = 0.5; 
                float RefractionStrength = u_refraction;
                
                // Map Shadertoy coordinates to View coordinates
                vec2 aspect = vec2(uResolution.x / uResolution.y, 1.0);
                vec2 p = (vUV - 0.5) * aspect;
                
                // Capsule Logic: Calculate distance to a horizontal line segment
                // half length of the straight part = (aspect.x - 1.0) / 2
                float h = max(0.0, (aspect.x - 1.0) * 0.5);
                float D = length(p - vec2(clamp(p.x, -h, h), 0.0));
                
                // Lens Center for Refraction Direction (Center point of the closest part of the segment)
                vec2 nearestPointOnSegment = vec2(clamp(p.x, -h, h), 0.0);
                
                // Alpha Mask
                float Edge = fwidth(D);
                float Alpha = 1.0 - smoothstep(CircleRadius - Edge, CircleRadius + Edge, D);
                
                if (Alpha <= 0.0) {
                    discard;
                }
                
                // Refraction Logic (Uses provided pow(6.0) math)
                float Refraction = pow(D / CircleRadius, 6.0) * RefractionStrength;
                
                // Refraction Direction (Points from current pixel towards the nearest point on the capsule spine)
                vec2 RefractOffsetLocal = normalize(nearestPointOnSegment - p) * Refraction;
                
                // If D is very small, normalize might be unstable
                if (D < 0.0001) RefractOffsetLocal = vec2(0.0);
                
                // Dispersion
                vec2 Dispersion = RefractOffsetLocal * 0.05;
                
                // Background Sampling Positions (in Screen Pixels)
                vec2 currentFragScreenPos = uViewPos + (vUV * uResolution);
                
                // Convert offsets to screen pixels
                // RefractOffsetLocal is normalized to res.y height. 
                // So OffsetPixels = RefractOffsetLocal * uResolution.y
                vec2 OffsetPixels = RefractOffsetLocal * uResolution.y;
                vec2 DispersionPixels = Dispersion * uResolution.y;
                
                vec2 posR = currentFragScreenPos + OffsetPixels - DispersionPixels;
                vec2 posG = currentFragScreenPos + OffsetPixels;
                vec2 posB = currentFragScreenPos + OffsetPixels + DispersionPixels;
                
                // Apply Magnification: Scale around view center (uViewPos + 0.5*uResolution typically, actually view center is uViewPos + offset)
                // BUT sampling logic computes distinct points. Let's scale positions relative to Capture Center?
                // Simpler: Just scale the UVs after computing them? No, that shifts outside capture rect.
                // Move the sample position towards the center of the capture rect to zoom in.
                
                vec2 captureCenter = uCaptureRect.xy + uCaptureRect.zw * 0.5;
                
                // Zoom in: move point closer to center.
                // point = center + (point - center) / mag
                
                // Scale factor for offset: should probably scale with resolution or allow pixel inputs
                vec2 offsetVec = vec2(u_offset_x, u_offset_y);

                posR = captureCenter + (posR - captureCenter) / u_magnification + offsetVec;
                posG = captureCenter + (posG - captureCenter) / u_magnification + offsetVec;
                posB = captureCenter + (posB - captureCenter) / u_magnification + offsetVec;

                // Convert to Texture UVs
                vec2 uvR = (posR - uCaptureRect.xy) / uCaptureRect.zw;
                vec2 uvG = (posG - uCaptureRect.xy) / uCaptureRect.zw;
                vec2 uvB = (posB - uCaptureRect.xy) / uCaptureRect.zw;
                
                // Flip Y
                uvR.y = 1.0 - uvR.y;
                uvG.y = 1.0 - uvG.y;
                uvB.y = 1.0 - uvB.y;
                
                // Sample with Blur
                vec2 pSize = 1.0 / uCaptureRect.zw;
                float bRad = u_blur_intensity; // Use existing blur uniform
                
                float r = get_blurred_color(screen_texture, uvR, pSize, bRad).r;
                float g = get_blurred_color(screen_texture, uvG, pSize, bRad).g;
                float b = get_blurred_color(screen_texture, uvB, pSize, bRad).b;
                
                vec3 finalColor = vec3(r, g, b);
                
                // --- Glass Polish: Outline & Shadow ---
                
                // 1. Subtle Thin Outline (Highlight at the very edge)
                float outlineWidth = 0.01;
                float outline = smoothstep(CircleRadius - outlineWidth - Edge, CircleRadius - outlineWidth, D) * 
                              (1.0 - smoothstep(CircleRadius - Edge, CircleRadius, D));
                finalColor += outline * 0.3; // White highlight
                
                // 2. Soft Inner Shadow (Depth effect)
                float innerShadow = smoothstep(CircleRadius - 0.15, CircleRadius, D);
                finalColor *= (1.0 - innerShadow * 0.08); // Darken edges slightly
                
                // 3. Top-Left Highlight (Faux Lighting)
                vec2 lightDir = normalize(vec4(-1.0, 1.0, 0.0, 0.0).xy);
                vec3 normal = vec3(normalize(p - nearestPointOnSegment), 1.0);
                float spec = pow(max(dot(normal.xy, lightDir), 0.0), 4.0);
                finalColor += spec * 0.1;

                FragColor = vec4(finalColor, Alpha);
            }
        """
    }
}
