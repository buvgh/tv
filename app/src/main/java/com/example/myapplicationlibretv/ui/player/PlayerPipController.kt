package com.example.myapplicationlibretv.ui.player

object PlayerPipController {
    private var enterPipAction: (() -> Unit)? = null

    fun attach(action: () -> Unit) {
        enterPipAction = action
    }

    fun detach(action: () -> Unit) {
        if (enterPipAction === action) {
            enterPipAction = null
        }
    }

    fun enterPictureInPictureIfPossible() {
        enterPipAction?.invoke()
    }
}
