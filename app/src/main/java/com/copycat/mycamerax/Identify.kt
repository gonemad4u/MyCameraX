package com.copycat.mycamerax

import android.content.res.AssetManager
import android.graphics.Bitmap

class Identify {
    external fun init(mgr: AssetManager?): Boolean
    external fun detect(bitmap: Bitmap?, use_gpu: Boolean): FloatArray?

    companion object {
        init {
            System.loadLibrary("identify")
        }
    }
}