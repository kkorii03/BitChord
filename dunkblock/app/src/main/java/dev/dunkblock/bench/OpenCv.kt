package dev.dunkblock.bench

import android.util.Log
import org.opencv.android.OpenCVLoader
import org.opencv.core.Core

object OpenCv {
    @Volatile var ready = false; private set
    @Volatile var status = "미초기화"; private set

    @Synchronized fun init(threads: Int) {
        if (!ready) {
            ready = try { OpenCVLoader.initLocal() } catch (t: Throwable) { Log.e("DunkBlock", "OpenCV", t); false }
            status = if (ready) "OpenCV ${Core.VERSION} 로드됨" else "OpenCV 로드 실패"
        }
        if (ready) Core.setNumThreads(threads)
    }
}
