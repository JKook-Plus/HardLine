package dev.hardline.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dev.hardline.gl.Pipeline
import dev.hardline.gl.PreviewParams

/** A window onto the composed picture. Each instance registers its own surface with the pipeline. */
@Composable
fun PreviewSurface(pipeline: Pipeline, params: PreviewParams, modifier: Modifier = Modifier) {
    val key = remember { Any() }
    val current = remember { arrayOf(params) }
    current[0] = params
    DisposableEffect(key) { onDispose { pipeline.removeWindow(key) } }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            SurfaceView(context).apply {
                holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = Unit
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                        pipeline.addWindow(key, holder.surface, width, height, false, current[0])
                    }

                    override fun surfaceDestroyed(holder: SurfaceHolder) {
                        pipeline.removeWindow(key)
                    }
                })
            }
        },
        update = { pipeline.setWindowParams(key, params) },
    )
}
