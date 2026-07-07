package com.autonomi.examples.antdemo

import android.content.Context
import com.sun.jna.Library
import com.sun.jna.Native

/// Plants the `HOME` env var into the process before any Rust code reads it.
/// `ant-core::config::home_dir()` reads `$HOME` / `$USERPROFILE` and aborts
/// the constructor with `HomeDirNotFound` otherwise — Android app processes
/// don't inherit a HOME by default.
///
/// Filed as a separate concern against ant-sdk; the proper fix is for the
/// FFI to accept an explicit data-dir argument so this libc dance isn't
/// needed in real apps.
internal interface LibC : Library {
    fun setenv(name: String, value: String, overwrite: Int): Int
    companion object { val INSTANCE: LibC = Native.load("c", LibC::class.java) }
}

object AntFfiBootstrap {
    @Volatile private var planted = false

    fun plantOnce(context: Context) {
        if (planted) return
        synchronized(this) {
            if (planted) return
            LibC.INSTANCE.setenv("HOME", context.filesDir.absolutePath, 1)
            planted = true
        }
    }
}
